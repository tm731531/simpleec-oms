# Platform Capabilities Model

> Rules for the `platform.capabilities` JSONB field that drives behavior differences between platforms. Replaces all `if platformCode == "shopify"` hardcoding. Every engineer touching platform-specific behavior must read this file.

---

## 1. Purpose

Platforms differ in their supported features: some have multi-location inventory, some support webhooks, some use async write operations. Historically, these differences are handled by checking the platform name or code in application code. This is a maintenance trap: every new platform requires hunting down all hardcoded checks and adding another branch.

**The capabilities model replaces all platform-name conditionals.** Behavior is driven by reading a JSONB field on the `platform` table. Adding a new platform requires only: inserting a row with the correct capabilities — zero code changes.

---

## 2. Schema

The `platform` table has a `capabilities` column:

```sql
capabilities JSONB NOT NULL DEFAULT '{}'
```

- `NOT NULL` — the column always has a value.
- `DEFAULT '{}'` — empty object means "all capabilities are false/disabled".
- Individual capabilities not present in the JSON are treated as their default value (always `false` for boolean capabilities).

No schema migration is needed when adding a new capability key. The application reads missing keys as the default value.

---

## 3. Known Capability Keys

| Key | Type | Default | Meaning |
|-----|------|---------|---------|
| `multiLocation` | boolean | `false` | Platform has per-location inventory management (Shopify = true). When true, inventory is tracked per `channel_location`; when false, `channel_location_id` is NULL in `sell_pack_inventory`. |
| `webhook` | boolean | `false` | Platform supports push event delivery. When true, OMS registers webhook endpoints with the platform. When false, OMS uses scheduled polling only. |
| `asyncInventory` | boolean | `false` | Platform inventory write operations are async and return a `task_id`. When true, Channel Job must persist `task_id` and poll for completion. When false, inventory update response is final. |
| `supportsShipment` | boolean | `false` | Platform supports the SHIP_ORDER callback API. When `true`, `ShipOrderHandler` calls the platform's fulfillment endpoint. When `false`, the handler skips the outbound call (platform does not expose a ship API). |
| `supportsReturnApproval` | boolean | `false` | Platform supports APPROVE_RETURN / REJECT_RETURN callback APIs. When `true`, `ApproveReturnHandler` and `RejectReturnHandler` call the platform's refund approval/rejection endpoints. When `false`, handlers skip the outbound call. |
| `supportsReturnFetch` | boolean | `false` | Platform supports a FETCH_RETURNS API. When `true`, `FetchReturnsHandler` actively polls the platform for return/refund records. When `false`, returns are only known via inbound webhook or order status data. |

All keys are boolean. Additional capability types (string, integer) may be added in the future — document them here before implementing.

### Handler → Capability Key Reference

| Handler | Capability Key Checked | Behaviour when `false` |
|---------|----------------------|------------------------|
| `ShipOrderHandler` | `supportsShipment` | Skips outbound platform API call; logs warning |
| `ApproveReturnHandler` | `supportsReturnApproval` | Skips outbound platform API call; logs warning |
| `RejectReturnHandler` | `supportsReturnApproval` | Skips outbound platform API call; logs warning |
| `FetchReturnsHandler` | `supportsReturnFetch` | Skips polling; no return records fetched from platform |

These handlers replaced previous `"cyberbiz".equalsIgnoreCase(platformCode)` guards (removed in V6). Adding a new platform that supports these APIs requires only setting the capability in a Flyway migration — no handler code changes.

---

## 4. Seed Values Per Platform

| Platform | capabilities JSON | Notes |
|----------|-----------------|-------|
| Shopify | `{"multiLocation": true}` | All other capabilities default to false |
| Shopee | `{"asyncInventory": true}` | `update_stock` returns task_id |
| Cyberbiz | `{"supportsShipment": true, "supportsReturnApproval": true, "supportsReturnFetch": true}` | Added by V6 migration. Supports SHIP_ORDER, APPROVE/REJECT_RETURN, and FETCH_RETURNS APIs. |
| Shopline | `{"webhook": true}` | Open API supports webhooks |
| Momo | `{}` | All defaults |
| Yahoo | `{}` | All defaults |
| PChome | `{}` | All defaults |
| Easystore | `{}` | All defaults |

Seed values are applied via Flyway migration. When onboarding a new platform, add a row to this table AND add the corresponding Flyway migration.

---

## 5. How to Read Capabilities

**Always use `.path("key").asBoolean(false)` with an explicit safe default.**

```java
// Read a boolean capability with a safe default
boolean isMultiLocation = platform.getCapabilities().path("multiLocation").asBoolean(false);
boolean supportsWebhook  = platform.getCapabilities().path("webhook").asBoolean(false);
boolean isAsyncInventory = platform.getCapabilities().path("asyncInventory").asBoolean(false);
```

`platform.getCapabilities()` returns a `JsonNode`. Using `.path("key")` instead of `.get("key")` avoids NullPointerException when the key is absent — `.path()` returns a `MissingNode` that safely returns the default in `.asBoolean(false)`.

**Never use `.get("key")` without a null check.** `.get()` returns `null` for missing keys.

```java
// FORBIDDEN — NullPointerException if key is absent
boolean isMultiLocation = platform.getCapabilities().get("multiLocation").asBoolean();

// CORRECT
boolean isMultiLocation = platform.getCapabilities().path("multiLocation").asBoolean(false);
```

---

## 6. Forbidden Patterns

The following patterns are **forbidden in all modules**. Code review must reject any PR containing these.

### String comparisons on platform code or name

```java
// FORBIDDEN — brittle, breaks when new platforms are added
if (platformCode.equals("shopify")) {
    // multi-location logic
}

// FORBIDDEN — case-insensitive doesn't fix the problem
if (platformCode.equalsIgnoreCase("shopify")) {
    // multi-location logic
}

// FORBIDDEN — partial match is worse
if (platformName.toLowerCase().contains("shopify")) {
    // multi-location logic
}

// FORBIDDEN — switch is just another form of the same problem
switch (platformCode) {
    case "shopify":
        // multi-location logic
        break;
    case "shopee":
        // async inventory logic
        break;
}

// FORBIDDEN — enum-based dispatch on platform name
PlatformType.fromCode(platformCode).isMultiLocation()
// (Do not create a PlatformType enum that encodes capabilities)
```

### Why these are forbidden

- Adding a new platform requires modifying existing switch/if chains — scattered across multiple files.
- Typos in string comparisons are silent bugs (no compile-time check).
- Platform names change in business context; platform codes might be aliased.
- Enum-encoded capabilities require code changes per platform, defeating the purpose of a data-driven model.

### The correct replacement

```java
// CORRECT — data-driven, no platform name in code
boolean isMultiLocation = platform.getCapabilities().path("multiLocation").asBoolean(false);
if (isMultiLocation) {
    // multi-location logic
}
```

---

## 7. Adding New Capability Keys

Follow these steps in order. Do not skip steps.

1. **Document the key here first.** Add a row to the Known Capability Keys table (Section 3) with key name, type, default, and meaning. Get the description reviewed before writing code.

2. **Write a Flyway migration** to set the capability value for all existing platforms.

   ```sql
   -- V{next_version}__add_capability_{key_name}.sql
   UPDATE platform SET capabilities = capabilities || '{"newCapability": true}'::jsonb
   WHERE platform_code IN ('shopify');  -- only platforms where it applies
   -- platforms not listed retain the default (false)
   ```

3. **Read the capability in code** using `.path("newCapability").asBoolean(false)`.

4. **Update seed values table** in this document (Section 4) to reflect the new key for each platform.

5. **Update channel-specific tests** to verify behavior under both `true` and `false` values.

**Never hard-set a capability in application code** (e.g., `if (platformCode.equals("shopify")) return true`). If the migration hasn't run yet, that is a deployment sequencing issue to solve with Flyway, not with code.

---

## 8. UI Implications

The frontend reads capabilities from the Channel API response. The channel entity exposed by `simpleec-web` must include the `capabilities` JSONB (or a typed projection of it) so the frontend can conditionally show/hide features.

| Capability | UI behavior when `true` | UI behavior when `false` |
|-----------|------------------------|------------------------|
| `multiLocation` | Show "Locations" tab in channel settings; show per-location inventory breakdown in inventory view | Hide location management; show single inventory quantity per SKU |
| `webhook` | Show webhook registration status and URL in channel settings | Show "Polling mode" indicator; no webhook URL shown |
| `asyncInventory` | Show "Sync Task" status indicator for inventory operations | Inventory sync feedback is immediate |

Frontend must read capabilities from the API response, not hardcode by platform name. The same forbidden-pattern rule applies to frontend code:

```typescript
// FORBIDDEN
if (channel.platformCode === 'shopify') { showLocationTab(); }

// CORRECT
if (channel.platform.capabilities.multiLocation === true) { showLocationTab(); }
```

---

## 9. Relationship to sell_pack_inventory

The `multiLocation` capability directly determines how inventory snapshots are structured in `sell_pack_inventory`.

### multiLocation = false (default)

- `sell_pack_inventory.channel_location_id` = `NULL`
- One row per `(sell_pack_id, channel_id)`
- Query: `WHERE channel_id = ? AND channel_location_id IS NULL`

### multiLocation = true (Shopify)

- `sell_pack_inventory.channel_location_id` = a `channel_location.id` value
- One row per `(sell_pack_id, channel_id, channel_location_id)`
- Only locations where `channel_location.is_sync_target = true` are actively synced
- Partial unique index ensures only ONE sync target per channel

### Channel Job inventory sync logic

```java
boolean isMultiLocation = platform.getCapabilities().path("multiLocation").asBoolean(false);

if (isMultiLocation) {
    // Fetch sync target locations from channel_location where is_sync_target = true
    List<ChannelLocation> syncTargets = channelLocationRepo
        .findByChannelIdAndIsSyncTarget(channelId, true);
    for (ChannelLocation location : syncTargets) {
        syncInventoryToLocation(sellPack, channel, location);
    }
} else {
    // Single inventory target, no location concept
    syncInventoryToChannel(sellPack, channel);
}
```

### Fetching Shopify inventory_item_id

Shopify inventory uses `inventory_item_id`, which is distinct from `variant_id`. These are not interchangeable.

- `variant_id`: identifies a product variant (used in order line items, product APIs)
- `inventory_item_id`: identifies the inventory record for that variant (used in inventory APIs)
- Relationship: 1 variant → 1 inventory_item_id (but the numeric values differ)

Store `inventory_item_id` in `sell_pack.platform_metadata` JSONB under the key `inventoryItemId`:

```json
{
  "inventoryItemId": "987654321"
}
```

Fetch `inventory_item_id` from Shopify's variant API when the product is first synced. Do not assume `variant_id == inventory_item_id`.

---

## 10. Querying Capabilities in DB (for Diagnostics)

To see all platforms and their capabilities:

```sql
SELECT platform_code, platform_name, capabilities
FROM platform
ORDER BY platform_code;
```

To find all platforms where multiLocation is enabled:

```sql
SELECT platform_code
FROM platform
WHERE (capabilities->>'multiLocation')::boolean = true;
```

To find platforms with no capabilities set (all defaults):

```sql
SELECT platform_code
FROM platform
WHERE capabilities = '{}'::jsonb;
```
