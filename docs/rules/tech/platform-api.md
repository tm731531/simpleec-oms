# Platform API Rules

> Rules for calling external platform APIs (Shopee, Shopify, Cyberbiz, Shopline, Momo, Yahoo, PChome, Easystore) from Channel Job. Every engineer writing or reviewing channel integration code must read this file.

---

## 1. Who Calls Platform APIs

**Only `simpleec-channel-job` module calls external platform APIs.** No other module (web, core, scheduler, common) may make outbound HTTP calls to platform endpoints.

- `simpleec-web` → exposes our own REST endpoints; never calls platform APIs
- `simpleec-core` → entities, mappers, services; no outbound HTTP
- `simpleec-scheduler` → fires Kafka messages with a timestamp; does not call platforms
- `simpleec-channel-job` → consumes Kafka messages, calls platform APIs, writes results to DB

If you find outbound platform API calls outside `simpleec-channel-job`, that is a layering violation. Raise it in code review.

---

## 2. Authentication Per Platform

| Platform | Auth Method | Token Field | Access Token Expiry | Refresh Token Expiry | Rotation |
|----------|-------------|-------------|--------------------|--------------------|----------|
| Shopee | HMAC-SHA256 signature + OAuth | `access_token` in query | 4 hours | 30 days | Yes — new refresh_token issued on each refresh |
| Shopify | Static header | `X-Shopify-Access-Token` | Offline: permanent; Online: 1h | 90 days (online) | Yes (online only) |
| Cyberbiz | HMAC-SHA1 signature | Request signing only; no bearer token | N/A | N/A | N/A |
| Shopline (Open API) | Static Bearer token | `Authorization: Bearer {token}` | None (static) | N/A | No |
| Shopline (Admin REST) | OAuth | `Authorization: Bearer {access_token}` | Short-lived | Rotating | Yes |
| Momo | Platform-specific | Per Momo integration spec | Per spec | Per spec | Per spec |
| Yahoo | Platform-specific | Per Yahoo integration spec | Per spec | Per spec | Per spec |
| PChome | Platform-specific | Per PChome integration spec | Per spec | Per spec | Per spec |
| Easystore | Platform-specific | Per Easystore integration spec | Per spec | Per spec | Per spec |

### Shopee Signature Construction

Base string (concatenate with NO separators, no commas, no newlines between fields):

```
{partner_id}{path}{timestamp}{access_token}{shop_id}
```

Sign with HMAC-SHA256. Common mistake: adding separator characters between fields. Do not.

### Cyberbiz Signature Construction

Algorithm: **HMAC-SHA1** (not HMAC-SHA256).

Signing string format (e.g., GET request to /v1/orders):

```
x-date: {date_value}\nGET /v1/orders HTTP/1.1
```

Do NOT prefix with `"request-line:"`. That prefix is wrong and will produce 401 errors.

Base URL is `api.cyberbiz.co` — not `api.cyberbiz.io`. Using `.io` silently fails or hits a wrong host.

---

## 3. Token Management Rules

1. **Refresh proactively at < 20% remaining TTL.** Do not wait until expiry. For a 4-hour Shopee token, refresh after ~3h 12m (when < 48 minutes remain).

2. **Persist the new token immediately after a successful refresh call, before using it.** Never use a refreshed token without first writing it to DB. If the process crashes after calling the platform but before saving, the next startup will detect expiry and re-refresh cleanly.

3. **For rotating refresh tokens (Shopee, Shopify online):** the platform invalidates the old refresh_token and issues a new one. Store the new refresh_token in the same transaction as the new access_token. If you discard the new refresh_token, the shop becomes permanently disconnected.

4. **Token failures are per-shop, never per-platform.** One shop's bad token must not block other shops on the same platform. Channel Job processes each shop's token independently. Token refresh errors → emit a `CHANNEL_AUTH_ERROR` event for that shop only; continue processing other shops.

5. **Never store tokens in memory only.** Tokens live in the `channel_auth` table. Channel Job reads from DB on startup and after each refresh.

---

## 4. Time Window Rules

**ALWAYS use `message.header.timestamp` as the base time for any time window calculation. NEVER call `Instant.now()` inside Channel Job to determine what period to fetch.**

Rationale: if a job is delayed (Kafka lag, retry), `Instant.now()` drifts forward and produces wrong time windows or gaps in data. `header.timestamp` is fixed at scheduling time and is idempotent.

```java
// CORRECT
Instant windowEnd = message.getHeader().getTimestamp();
Instant windowStart = windowEnd.minus(Duration.ofHours(1));

// FORBIDDEN
Instant windowEnd = Instant.now();  // NO. Do not do this.
```

Rules:
- Scheduler determines the period and encodes it as `header.timestamp` when publishing to Kafka.
- Channel Job reads `header.timestamp` and computes its own start/end window from it.
- Scheduler never pre-computes window start/end — that is Channel Job's responsibility.
- Channel Job never re-derives time from the wall clock — that is Scheduler's responsibility.

---

## 5. Rate Limiting

| Platform | Limit | Header to Monitor | Behavior on 429 |
|----------|-------|--------------------|-----------------|
| Shopify (Standard plan) | 2 req/s (leaky bucket) | `X-Shopify-Shop-Api-Call-Limit: {used}/{max}` | Backoff + retry |
| Shopify (Advanced plan) | 4 req/s (leaky bucket) | `X-Shopify-Shop-Api-Call-Limit: {used}/{max}` | Backoff + retry |
| Shopee | Per-partner quota | No explicit header; 429 body contains error code | Backoff + retry |
| Cyberbiz | Max 20 records/page | N/A (pagination limit, not rate limit) | N/A |
| Shopline (Open API) | 20 req/s | N/A | Backoff + retry |
| Shopline (Admin REST) | 4 req/s (leaky bucket) | N/A | Backoff + retry |

**Exponential backoff on 429:**
- Initial delay: 1 second
- Multiplier: 2x
- Max delay: 60 seconds
- Max attempts: 5 (then emit error event, do not infinite retry in same job run)

**Shopify:** read `X-Shopify-Shop-Api-Call-Limit` on every response. If `used/max >= 0.8`, insert a 500ms sleep before the next call. Do not wait until 429.

---

## 6. Pagination Rules

### Cursor-based pagination (preferred by most platforms)

| Platform | Cursor field | Has-more signal |
|----------|-------------|-----------------|
| Shopify | `Link` response header (`rel="next"`) | Presence of `rel="next"` in Link header |
| Shopline | `previous_id` query param | Response count < per_page |
| Shopee | `next_cursor` in response body | `more` boolean in response body |

**Cursor pages cannot be parallelized.** You must fetch page N before you know the cursor for page N+1. Do not attempt concurrent cursor pagination.

**Shopline-specific:** use `previous_id` for pagination, not `page × per_page` offset. Offset pagination breaks when total records exceed 10,000. Any code using `page * per_page` offset on Shopline is a bug.

### Offset-based pagination

Some platforms (Cyberbiz, older Momo endpoints) use page/offset. Maximum page sizes:
- Cyberbiz: 20 records per page (hard limit)

Always check `has_more` or compare returned count vs requested page size to detect last page. Do not assume a short page means no more data — some platforms return partial pages.

---

## 7. List vs Detail API Pattern

Not all platforms return full order data in list APIs. Failing to call detail APIs when required results in missing fields (amount, address, items).

| Platform | List API returns full order? | Detail API required? | Batch size |
|----------|-----------------------------|--------------------|-----------|
| Shopee | No — returns `order_sn` + `order_status` only | Yes — `get_order_detail` | Max 50 per call |
| Cyberbiz | No — returns summary only | Yes — `GET /v1/orders/{id}` per order | 1 per call (no batch) |
| Shopify | Yes (with correct fields) | No (fields selectable in list) | N/A |
| Shopline | Yes | No | N/A |
| Momo | Yes | No | N/A |
| Easystore | Yes | No | N/A |

### Shopee get_order_detail

Must explicitly declare `response_optional_fields` in every call. Omitting this field returns a minimal response with no items, no address, no amounts. Required fields:

```
item_list, recipient_address, package_list, total_amount, pay_time, buyer_username
```

Example (abbreviated):

```java
params.put("response_optional_fields",
    "item_list,recipient_address,package_list,total_amount,pay_time,buyer_username");
```

Batch up to 50 `order_sn` values per call. Collect all `order_sn` from list API first, then batch into groups of 50 for detail calls.

### Cyberbiz

No batching available. Each order requires an individual `GET /v1/orders/{id}` call. Respect rate limits when iterating over many orders.

---

## 8. Async Write APIs

Some platform write operations return a task ID instead of a final result. You MUST handle these correctly.

**Current async write operations:**

| Platform | Operation | Returns | Poll endpoint |
|----------|-----------|---------|---------------|
| Shopee | `update_stock` | `task_id` | `get_task_result` |

**Rules for async writes:**

1. **Persist `task_id` to DB immediately** after receiving it, before doing anything else. Column: `channel_task.task_id`, `channel_task.status = PENDING`. If the job crashes before persisting, the write is untracked and inventory state is unknown.

2. **Poll `get_task_result` until terminal state.** Terminal states: `SUCCESS`, `FAILED`. Do not assume success after receiving `task_id`.

3. **Always check `failure_list` in the poll result.** HTTP 200 with a non-empty `failure_list` means partial failure. Log every item in `failure_list` with its error code. Update `channel_task.status = PARTIAL_FAILED` and record which item IDs failed.

4. **Polling timeout:** if polling exceeds 10 minutes without terminal state, mark `channel_task.status = TIMEOUT` and emit an error event. Do not poll indefinitely.

5. **Never assume all success on HTTP 200.** HTTP 200 indicates the API call was accepted, not that the operation succeeded.

---

## 9. Webhook Receiver Rules

1. **Respond HTTP 200 immediately** upon receiving any platform webhook. Do not process the payload before responding. Platforms interpret slow responses as failures.

2. **Process webhook payload via Kafka** after responding. Webhook controller → publish to Kafka topic → Channel Job consumes and processes.

3. **Idempotency is mandatory.** Platforms deliver webhooks at-least-once. Implement idempotency using the platform's event ID:
   - Shopify: deduplicate on `X-Shopify-Event-Id` header
   - Shopee: deduplicate on `order_sn` + `status` combination
   - Check dedup key in Redis (TTL 24h) before processing

4. **Shopee webhook threshold:** Shopee disables webhooks for a shop if the failure rate exceeds 30%. If our receiver returns non-200 consistently, the shop loses real-time sync. Monitor webhook endpoint availability carefully.

5. **Shopline webhook timeout:** Shopline requires a response within 5 seconds. If our processing path is slow, the 200 response must be sent before any processing starts. After 5s, Shopline retries (up to 19 times over 48 hours).

6. **OMS is a passive sync party.** Accept any order state transition from the platform. Do not reject or ignore webhook events because the state seems unexpected. Record the state as received and let the business logic layer decide what to do.

---

## 10. Cross-Platform Pitfalls Reference

These are real mistakes that have been documented. Check each before code review.

| Pitfall | Platform | Wrong | Correct |
|---------|----------|-------|---------|
| Base URL | Cyberbiz | `api.cyberbiz.io` | `api.cyberbiz.co` |
| HMAC algorithm | Cyberbiz | HMAC-SHA256 | HMAC-SHA1 |
| Signing prefix | Cyberbiz | `"request-line: GET /v1/orders HTTP/1.1"` | `"x-date: ...\nGET /v1/orders HTTP/1.1"` (no prefix) |
| Shopee base string | Shopee | Fields joined with separator | Fields concatenated directly, NO separators |
| Optional fields | Shopee | Omit `response_optional_fields` | Must explicitly declare all required fields |
| Inventory item ID | Shopify | Use `variant_id` for inventory operations | Use `inventory_item_id` (different ID, fetched separately) |
| Inventory adjustment | Shopify | `adjust` without knowing current qty | Use `set` (absolute) or fetch current qty before `adjust` |
| User-Agent header | Shopline Open API | Omit User-Agent | Must send `User-Agent: {handle}` (mandatory) |
| Pagination type | Shopline | Page offset (`page * per_page`) | Cursor via `previous_id` |
| Token rotation | Shopee / Shopify | Discard new refresh_token | Must persist new refresh_token immediately |
| Time base | All | `Instant.now()` in Channel Job | `message.header.timestamp` |
| Platform capability check | All | `if (platformCode.equals("shopify"))` | `platform.getCapabilities().path("multiLocation").asBoolean(false)` |

---

## 11. Capability-Driven Behavior

Do not hardcode platform-name checks to vary behavior. Read `platform.capabilities` JSONB instead.

```java
// FORBIDDEN
if (platformCode.equals("shopify")) {
    // multi-location logic
}

// CORRECT
boolean isMultiLocation = platform.getCapabilities().path("multiLocation").asBoolean(false);
if (isMultiLocation) {
    // multi-location logic
}
```

See `capabilities-model.md` for full rules on the capabilities pattern.
