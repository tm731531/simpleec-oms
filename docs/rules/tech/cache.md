# Cache Rules

> Rules for Redis usage in SimpleEC OMS: what is cached, how deduplication works, key naming conventions, failure handling, and what must NOT be cached. Read before adding any Redis usage.

---

## 1. What Redis Is Used For

**Current uses:**

| Use Case | Namespace | Purpose |
|----------|-----------|---------|
| Order deduplication | `dedup` | Prevent duplicate order processing on webhook at-least-once delivery |

**Planned (not yet implemented):**

| Use Case | Namespace | Notes |
|----------|-----------|-------|
| Session cache | `session` | Do not implement until auth is finalized |
| Hot data cache | `hot` | Channel/platform metadata (infrequently changing) |

Do not add new Redis uses without documenting them in this file. If you are adding a new Redis key pattern, update this file in the same PR.

---

## 2. Deduplication Pattern

### Key Format

```
dedup:{channelId}:{channelOrderId}:{orderHash}
```

| Segment | Type | Example |
|---------|------|---------|
| `dedup` | Literal namespace | `dedup` |
| `{channelId}` | Long (channel table PK) | `42` |
| `{channelOrderId}` | String (platform's order ID) | `SH240315001` |
| `{orderHash}` | SHA-256 hex (first 16 chars sufficient) | `a3f8c2d1e9b04567` |

**TTL: 24 hours.** Set on write. Do not extend TTL on read.

### Hash Construction

The `orderHash` is a SHA-256 digest of a **sorted JSON** of the order's mutable fields.

Mutable fields to include in hash:
- `status`
- `total_amount`
- `shipping_fee`
- `tracking_number`
- `recipient_address` (normalized: lowercase, trimmed)
- `updated_at`

Rules:
- Sort JSON keys alphabetically before hashing (ensures field order does not affect hash).
- Normalize string values: trim whitespace, lowercase where semantically safe.
- Null fields: include as JSON `null` (do not omit them — omission changes the hash).
- Do not include immutable fields (order ID, channel ID, created_at) in the hash — they cannot change.

```java
// Example (pseudocode)
Map<String, Object> mutableFields = new TreeMap<>();  // TreeMap sorts keys
mutableFields.put("status", order.getStatus());
mutableFields.put("total_amount", order.getTotalAmount());
mutableFields.put("tracking_number", order.getTrackingNumber());
// ... add all mutable fields ...
String json = objectMapper.writeValueAsString(mutableFields);
String hash = DigestUtils.sha256Hex(json).substring(0, 16);
```

---

## 3. Dual-Layer Deduplication

Redis alone is insufficient: it has a fixed TTL and can be evicted under memory pressure. Database alone is slow for high-throughput webhook bursts. Use both layers.

### Flow

```
Incoming webhook event
        |
        v
[Layer 1] Redis: GET dedup:{channelId}:{channelOrderId}:{orderHash}
        |
   Key exists? ─── YES ──> SKIP (already processed, same content)
        |
       NO
        |
        v
[Layer 2] DB: SELECT FROM channel_order
              WHERE channel_id = ? AND channel_order_id = ?
        |
   Not found? ──> INSERT new order ──> SET Redis key (TTL 24h)
        |
   Found, same hash? ──> SKIP
        |
   Found, different hash? ──> UPDATE order ──> SET Redis key (new hash, TTL 24h)
```

### Decision Table

| Redis result | DB result | Action |
|-------------|-----------|--------|
| Key exists | — | SKIP. Do not query DB. |
| Key missing | Row not found | INSERT new order + SET Redis key |
| Key missing | Row found, same hash | SKIP + SET Redis key (restore cache) |
| Key missing | Row found, different hash | UPDATE order + SET Redis key (new hash) |

**Why "found, same hash" restores the cache:** Redis TTL expired but DB still has the record. Re-populate Redis to avoid repeated DB lookups for the same event.

---

## 4. Key Naming Convention

Pattern: `{namespace}:{scope}:{identifier}`

Rules:
1. **Namespace** is a short feature name: `dedup`, `session`, `hot`. Never use generic names like `cache` or `data`.
2. **Always include TTL** when writing a key. Never write a key without expiry.
3. **Namespace by feature**, not by data type. Bad: `string:order:123`. Good: `dedup:42:SH001:a3f8`.
4. **Colons are the only separator.** Do not use `/`, `.`, `_` between key segments.
5. **Keep keys short.** If an identifier is long, use a hash or abbreviation as the final segment.

### Current Key Patterns

| Key Pattern | TTL | Written By | Read By |
|-------------|-----|-----------|---------|
| `dedup:{channelId}:{channelOrderId}:{orderHash}` | 24h | Channel Job (webhook handler) | Channel Job (dedup check) |

---

## 5. Cache Invalidation Rules

**For deduplication keys:** do not manually invalidate. TTL-based expiry is intentional. The 24h window covers realistic webhook retry windows for all supported platforms (Shopline retries over 48h, but hash-based dedup handles re-delivery correctly — an unchanged event hits the same hash and is skipped).

**For future hot data cache (when implemented):**
- Invalidate on DB write that changes the cached data.
- Write-through pattern: update DB first, then delete (not update) the cache key.
- Delete rather than update cache on write to avoid stale-read windows during write propagation.
- Do NOT use read-through caching for data with complex invalidation logic (e.g., platform capabilities that affect business logic). Read from DB, cache the read, delete on write.

**Consistency window:** between a DB write and cache delete, a stale read is possible. For dedup this is acceptable (worst case: a duplicate event is processed once more in a tiny window). For business-critical data (prices, inventory), design the application to tolerate a brief stale read or use write-through with a short TTL.

---

## 6. What NOT to Cache

| Data | Reason NOT to cache |
|------|-------------------|
| Authentication tokens (channel_auth) | Security: if Redis is compromised, all tokens are exposed. Tokens already live in DB with row-level security. |
| Large JSONB blobs (platform_metadata, raw webhook payloads) | Memory cost. These blobs can be hundreds of KB. Cache only the fields you need. |
| Real-time inventory quantities | Too volatile. Cache staleness would cause incorrect inventory sync decisions. Always read from DB for inventory computations. |
| Order state used in business logic decisions | State must be consistent. Use DB (with appropriate locking) for state transitions, not cache. |
| Platform API responses | Responses may change at any time (order status updates). Caching them would mask real state changes. |
| Data whose TTL is unclear | If you cannot define a correct TTL, do not cache it. An incorrect TTL is worse than no cache. |

---

## 7. Redis Failure Handling

**Redis failure must NOT block order processing.** Redis is an optimization layer, not a critical dependency for correctness.

### Deduplication on Redis failure

```
Redis connection fails
        |
        v
Log warning (do not throw)
        |
        v
Fall through to Layer 2 (DB check)
        |
        v
Process as if Redis returned no result
```

The DB is the source of truth for deduplication. Redis is a performance optimization. A Redis outage increases DB load (more dedup queries) but does not cause incorrect behavior.

### Implementation pattern

```java
boolean redisHit = false;
try {
    redisHit = redisTemplate.hasKey(dedupKey);
} catch (RedisConnectionFailureException e) {
    log.warn("Redis unavailable for dedup check, falling through to DB: {}", e.getMessage());
    // redisHit remains false → falls through to DB check
}
```

Do not use `@Cacheable` annotations for dedup logic. The fallback behavior requires explicit control.

### Circuit breaker (future)

When Redis failure rate is high, consider wrapping Redis calls in a circuit breaker (Resilience4j) to avoid repeated connection timeouts degrading throughput. Not required for MVP, but design the Redis client wrapper with this in mind.

---

## 8. Data Types in Use

Use only the Redis data types that are currently needed. Do not introduce new data types without documenting the use case here.

| Redis Type | Current Use | Notes |
|-----------|-------------|-------|
| String (`SET`/`GET`/`EXISTS`) | Dedup keys | Value is empty string (key existence is the signal, not the value) |
| Hash | Not yet used | Candidate for: channel token metadata, platform config snapshot |
| List | Not yet used | — |
| Set | Not yet used | — |
| Sorted Set | Not yet used | — |

**Dedup key value:** store an empty string `""` or `"1"`. The value does not matter — only the key's existence is checked. Do not store the order payload as the value (memory waste, and the DB is the correct storage layer for that).

---

## 9. Configuration Reference

Redis is configured in `simpleec-app/src/main/resources/application.yml`. Connection pooling uses Lettuce (Spring Boot default).

Key config properties:
```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      timeout: 2000ms           # command timeout — keep short to enable fast failover
      lettuce:
        pool:
          max-active: 8
          max-idle: 4
          min-idle: 1
          max-wait: 1000ms      # pool acquisition timeout
```

Keep `timeout` at 2000ms or below. A slow Redis response should degrade gracefully, not block order processing for extended periods.
