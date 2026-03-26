---
name: SimpleEC OMS Design Rules
description: Project-specific design patterns and SOLID application for e-commerce order management
type: rule
---

# SimpleEC OMS - Design Rules

**Last Updated**: 2026-03-16 | **Maturity**: ⭐⭐⭐⭐ (Production)

## Current Design Assessment

### Strengths
- ✅ **Event-Driven Architecture**: Kafka-based message flow is clean and decoupled
- ✅ **Module Separation**: 11 modules with clear responsibilities (channel, core, api, jobs)
- ✅ **Channel Adapter Pattern**: Each platform (Shopee, Momo, Yahoo, PChome, Cyberbiz) has independent logic
- ✅ **Error Handling**: DefaultErrorHandler with DLT (Dead Letter Topic) for poison pills
- ✅ **Observability**: OTEL Agent integrated, Grafana dashboards in place
- ✅ **Database Safety**: AES-256-GCM encryption for PII, transparent at MyBatis layer

### Weaknesses / Missing
- ⚠️ **External API Resilience**: Platform API calls (Shopee, Momo) lack Circuit Breaker pattern
  - Risk: Single timeout blocks entire channel job
  - Impact: Cascading failure across order processing
- ⚠️ **Message Schema Versioning**: No formal schema registry for Kafka topics
  - Risk: Breaking changes go unnoticed until production
  - Impact: Version mismatch between producers/consumers
- ⚠️ **Channel Adapter Consistency**: Platform-specific time windows, pagination not standardized
  - Risk: Subtle bugs in one platform logic
  - Impact: Different behavior across channels

---

## Part 1: What to Change (Prioritized)

### Priority 1: Circuit Breaker for External Platform APIs 🔴

**Location**: `simpleec-channel-job/src/main/java/com/simpleec/channel/job/`

**Problem**:
```
When Shopee API is slow/down:
→ Channel job blocks waiting for response
→ All pending Shopee orders stuck
→ No timeout failover
→ Cascades to other channels (resource contention)
```

**Solution**: Add Resilience4j Circuit Breaker

**Affected Files**:
- `ShopeeChannelAdapter.java` (API calls)
- `MomoChannelAdapter.java` (API calls)
- `YahooChannelAdapter.java` (API calls)
- `PChomeChannelAdapter.java` (API calls)
- `CyberbizChannelAdapter.java` (API calls)

**How to Apply**:
```java
// Before: Direct API call
public List<Order> fetchOrders(String shopeeParams) {
    return shopeeApiClient.getOrders(shopeeParams);  // Can hang forever
}

// After: With Circuit Breaker
@CircuitBreaker(name = "shopeeApiBreaker", fallbackMethod = "fallback_fetchOrders")
public List<Order> fetchOrders(String shopeeParams) {
    return shopeeApiClient.getOrders(shopeeParams);
}

public List<Order> fallback_fetchOrders(String shopeeParams, Exception e) {
    logger.error("Shopee API failed, using cached orders", e);
    return cachedOrderRepository.getLatest(shopeeParams);
}
```

**Testing**: Add `@Transactional` test that simulates API timeout

**Priority**: HIGH (stability)
**Effort**: 2-3 hours
**Benefit**: Prevents cascade failures, improves resilience

---

### Priority 2: Channel Adapter Consistency Documentation 🟡

**Location**: `docs/3-EVENT-FLOW/CHANNEL_ADAPTERS.md` (new or expand)

**Problem**:
```
Each platform has different:
- Time window calculation (last_sync_time, fetch_window_minutes)
- Pagination strategy (page/size vs offset/limit)
- Error retry logic (immediate, exponential backoff)
- Date format (ISO-8601, Unix timestamp, custom)

Risk: Copy-paste bugs, inconsistent behavior across channels
```

**Solution**: Standardize and document

**What to Document**:
1. **Time Window Calculation**: How each platform determines "what's new"
2. **Pagination Contract**: Max items per request, rate limiting
3. **Error Handling**: Which errors retry, which fail immediately
4. **Date Format**: What timestamp format each API returns
5. **Idempotency**: How to handle duplicate fetches safely

**Template**:
```markdown
## Shopee Channel Adapter

### Time Window
- Uses: last_sync_time (Unix timestamp)
- Fetch window: 5 minutes max (API rate limit)
- Retains: Last 30 days
- Behavior: If fetch fails, next sync tries again (no gap)

### Pagination
- Max items per request: 50
- Strategy: page/size (not offset/limit)
- Rate limit: 100 req/min per shop

### Error Handling
- Network timeout (3s): Retry exponential backoff
- HTTP 429 (rate limit): Wait 60s, retry
- HTTP 401 (auth failed): Skip channel, alert

### Date Format
- Input: Unix timestamp (seconds)
- Processing: Convert to LocalDateTime UTC
- Storage: Database TIMESTAMP
```

**Effort**: 4-6 hours (research + doc)
**Priority**: MEDIUM (prevents bugs, helps onboarding)
**Benefit**: Team clarity, reduced platform-specific bugs

---

### Priority 3: Message Schema Registry (Future) 🟢

**Location**: Would be new: `infra/schema-registry/`

**Problem**:
```
Kafka topics have implicit schemas (JSON structure)
No formal versioning or compatibility checking
→ Old consumers break when producer changes structure
```

**When to Apply**: Only if you start getting schema mismatch errors in production
**Current Status**: NOT URGENT (works fine now)
**Solution When Needed**: Confluent Schema Registry or similar

---

## Part 2: What NOT to Touch (Red Lines)

🛑 **Do not refactor these** (they work, touching them risks stability):

1. **Kafka Message Flow** (Topics, Producers, Consumers)
   - Location: All `*-job` modules
   - Why locked: Message order and routing are critical
   - Risk: 100+ orders in flight at any time
   - If you see an issue: Debug first, don't restructure

2. **MyBatis-Plus ORM Layer** (mappers, entities)
   - Location: `simpleec-core/src/main/java/com/simpleec/core/`
   - Why locked: Complex query optimization already done
   - Risk: SQL performance regression, transaction isolation breaks
   - If you need new queries: Ask for code review

3. **11-Module Boundary Separation**
   - Why locked: Module dependencies are clean and intentional
   - Risk: Adding cross-module imports leads to cycles, tight coupling
   - Rule: New features go in appropriate module, don't break modules apart

4. **Encryption Layer** (AES-256-GCM for PII)
   - Location: `simpleec-core` encryption utilities
   - Why locked: Security-critical, compliance-related
   - Risk: Accidental plaintext leakage, audit failures
   - If you need to encrypt new fields: Use existing utility, test thoroughly

---

## Part 3: SOLID Application (Project-Specific)

### Single Responsibility
✅ Already applied well:
- `ShopeeChannelAdapter`: Only handles Shopee platform logic
- `OrderService`: Only orchestrates order processing
- `MessageProducer`: Only publishes events

❌ Watch out for:
- Don't add "also handle caching" to channel adapters
- Don't add "validation" to adapters (belongs in service layer)

---

### Open/Closed Principle
✅ Already applied:
- Adding new platform → New adapter class + Kafka topic
- No modification to existing adapters
- NewPlatformAdapter extends ChannelAdapter interface

✅ How to maintain:
- New platform? → `NewPlatformChannelAdapter.java` + config entry
- New order status? → Add to `OrderStatus` enum, extend handlers
- Don't modify existing platform adapters unless fixing a bug

---

### Liskov Substitution
✅ Already safe:
- All ChannelAdapters are substitutable
- All OrderStatusHandlers behave consistently

❌ Don't violate:
```java
// Bad: Subclass that breaks contract
class YahooChannelAdapter extends ChannelAdapter {
    @Override
    public void fetchOrders() {
        // Does nothing! Breaks interface contract
        logger.info("Yahoo adapter not implemented");
    }
}

// Good: Either implement or throw NotImplementedError + alert
class YahooChannelAdapter extends ChannelAdapter {
    @Override
    public void fetchOrders() {
        throw new UnsupportedOperationException("Yahoo API deprecated");
    }
}
```

---

### Dependency Inversion
✅ Already applied:
- ChannelAdapter is abstraction, concrete implementations depend on it
- OrderService depends on OrderRepository interface, not direct DB queries
- MessageProducer abstracted, implementations swappable

---

## Part 4: Roadmap & Effort Estimates

| Task | Effort | Priority | Depends | Status |
|------|--------|----------|---------|--------|
| Add Circuit Breaker to channel adapters | 2-3h | HIGH | None | TODO |
| Document Channel Adapter behavior | 4-6h | MEDIUM | Above or parallel | TODO |
| Add message schema validation | 4-8h | LOW | None | NOT URGENT |
| Extend observability to channel adapters | 2-3h | MEDIUM | None | TODO |

---

## Part 5: Code Review Checklist (When Modifying)

Before submitting changes to this project:

- [ ] Does this change apply only to non-red-line code?
- [ ] Is there a Circuit Breaker or retry logic for external API calls?
- [ ] Does the new code follow existing channel adapter pattern?
- [ ] Are error cases logged with context (order ID, platform, timestamp)?
- [ ] Is encryption applied correctly for any new PII fields?
- [ ] Are Kafka messages properly typed and versioned?
- [ ] Tests cover happy path + error scenarios?

---

## Reference

**Global Rules**: See `/home/tom/.claude/projects/-home-tom/DESIGN_PRINCIPLES_RULE.md`

**OMS-Specific Docs**:
- `docs/3-EVENT-FLOW/CORE_CONTRACTS.md` — Message schemas
- `WORK_PRINCIPLES.md` — Operational guidelines
- `README.md` — Architecture overview
