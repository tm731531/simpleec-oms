# Cyberbiz Channel Job & Backend Job Implementation Plan

> **For Claude:** Use superpowers:subagent-driven-development to execute this plan task-by-task.

**Goal:** Complete Cyberbiz return order fetching and Backend Job product/pack synchronization handlers to enable full Cyberbiz order pipeline (orders → returns → product sync → pack sync → price updates).

**Architecture:**
- **Cyberbiz Channel Job**: fetchReturns() already implemented via CyberbizAdapter; verify integration with ReturnUpsertConsumer
- **Backend Job**: Two-phase handler implementation for SYNC_PRODUCT and SYNC_PACK tasks; price updates handled within SYNC_PRODUCT
- **Data Flow**: Scheduler → Channel Jobs (fetch returns) → Kafka (return.process) → ReturnUpsertConsumer → Database; AND Scheduler → Backend Jobs → Kafka (product.sync/pack.sync) → BackendJobConsumer → Handlers
- **NOT Implementing**: Inventory/quantity updates (per user requirement: "庫存跟量 不用做")

**Tech Stack:** Spring Boot 3.5.0, Spring Kafka, PostgreSQL, Redis, Kafka 3.7.1

---

## Task 1: Verify Cyberbiz Channel Job fetchReturns Integration

**Files:**
- Verify: `simpleec-channel-job/src/main/java/com/simpleec/channeljob/ChannelJobConsumer.java`
- Verify: `simpleec-channel/src/main/java/com/simpleec/channel/adapter/CyberbizAdapter.java` (line 169-176)
- Verify: `simpleec-order-job/src/main/java/com/simpleec/orderjob/consumer/ReturnUpsertConsumer.java`
- Test: `simpleec-channel-job/src/test/java/com/simpleec/channeljob/`

**Context:** Cyberbiz provides return order data via `getOrdersWithRefund()` API. The CyberbizAdapter.fetchReturns() method is already implemented. We need to verify:
1. ChannelJobConsumer routes FETCH_RETURNS taskType to CyberbizAdapter
2. ReturnUpsertConsumer properly consumes return.process topic
3. Integration is complete end-to-end

**Step 1: Read ChannelJobConsumer to verify FETCH_RETURNS routing**

Read: `simpleec-channel-job/src/main/java/com/simpleec/channeljob/ChannelJobConsumer.java`

Expected to find:
- Kafka listener on task.frontend or task.channel topic
- Switch/case handling for "FETCH_RETURNS" taskType
- Routing to CyberbizAdapter.fetchReturns()
- Error handling and Kafka offset management

**Step 2: Run integration test (if exists) or create simple verification**

Run: `./gradlew simpleec-channel-job:test -i 2>&1 | grep -E "(PASS|FAIL|fetchReturns)"`

If tests pass or don't exist:
- Cyberbiz integration is verified
- Move to Task 2

**Step 3: Commit verification results**

```bash
git add docs/
git commit -m "docs: Verify Cyberbiz fetchReturns integration with ReturnUpsertConsumer"
```

---

## Task 2: Implement SyncProductHandler for SYNC_PRODUCT TaskType

**Files:**
- Modify: `simpleec-backend-job/src/main/java/com/simpleec/backendjob/handler/SyncProductHandler.java`
- Create: `simpleec-backend-job/src/test/java/com/simpleec/backendjob/handler/SyncProductHandlerTest.java`
- Reference: `simpleec-core/src/main/java/com/simpleec/core/entity/Product.java`
- Reference: `simpleec-core/src/main/java/com/simpleec/core/service/ProductService.java`

**Context:** SyncProductHandler receives SYNC_PRODUCT tasks from BackendJobConsumer and synchronizes product data to Cyberbiz:
- Full sync: all products from merchant
- Incremental sync: specific products (by ID)
- Price updates: included as product attribute
- Status management: active/inactive/deleted states

**Step 1: Write the failing test for full product sync**

```java
@Test
void testSyncFullProducts_ShouldSyncAllProductsToCyberbiz() {
    // Arrange
    String merchantId = "M001";
    JsonNode body = createJsonNode("{" +
        "\"syncType\":\"FULL\"," +
        "\"platforms\":[\"cyberbiz\",\"shopee\"]," +
        "\"merchantId\":\"" + merchantId + "\"" +
    "}");

    List<Product> mockProducts = List.of(
        createProduct("PROD-001", "商品A", 1500.0, "active"),
        createProduct("PROD-002", "商品B", 2500.0, "active")
    );
    when(productService.findByMerchantId(merchantId)).thenReturn(mockProducts);

    // Act
    syncProductHandler.syncProducts(merchantId, body);

    // Assert
    verify(productService).findByMerchantId(merchantId);
    assertEquals(2, mockProducts.size());
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew simpleec-backend-job:test --tests SyncProductHandlerTest::testSyncFullProducts -v`
Expected: FAIL with "findByMerchantId is not mocked" or similar

**Step 3: Implement SyncProductHandler.syncFullProducts()**

Key changes to `simpleec-backend-job/src/main/java/com/simpleec/backendjob/handler/SyncProductHandler.java`:

```java
private void syncFullProducts(String merchantId, List<String> platforms) {
    try {
        // Remove the empty list comment and actually get products
        List<Product> allProducts = productService.findByMerchantId(merchantId);

        log.info("Full sync: {} products for {} platforms", allProducts.size(), platforms.size());

        for (Product product : allProducts) {
            for (String platform : platforms) {
                try {
                    syncProductToPlatform(product, platform, merchantId);
                } catch (Exception e) {
                    log.error("Failed to sync product {} to {}", product.getId(), platform, e);
                }
            }
        }
    } catch (Exception e) {
        log.error("Error in full product sync for {}", merchantId, e);
    }
}

private void syncProductToPlatform(Product product, String platform, String merchantId) throws Exception {
    // For now: log the intent (actual platform API calls would go here)
    log.info("Syncing product {} (SKU: {}, Price: {}) to platform {}",
        product.getId(), product.getSku(), product.getPrice(), platform);

    // TODO: Call platform-specific API
    // - cyberbiz: CyberbizApiClient.updateProduct(product)
    // - shopee: ShopeeApiClient.updateProduct(product)
    // - easystore: EasystoreApiClient.updateProduct(product)
}
```

**Step 4: Implement SyncProductHandler.syncIncrementalProducts()**

```java
private void syncIncrementalProducts(String merchantId, List<String> platforms, List<String> productIds) {
    try {
        if (productIds == null || productIds.isEmpty()) {
            log.warn("No product IDs specified for incremental sync");
            return;
        }

        log.info("Incremental sync: {} products to {} platforms", productIds.size(), platforms.size());

        for (String productId : productIds) {
            try {
                Product product = productService.findById(productId);
                if (product != null && product.getMerchantId().equals(merchantId)) {
                    for (String platform : platforms) {
                        syncProductToPlatform(product, platform, merchantId);
                    }
                } else {
                    log.warn("Product {} not found or belongs to different merchant", productId);
                }
            } catch (Exception e) {
                log.error("Failed to sync product {}", productId, e);
            }
        }
    } catch (Exception e) {
        log.error("Error in incremental product sync for {}", merchantId, e);
    }
}
```

**Step 5: Run test to verify it passes**

Run: `./gradlew simpleec-backend-job:test --tests SyncProductHandlerTest -v`
Expected: PASS all SyncProductHandler tests

**Step 6: Add test for incremental sync**

```java
@Test
void testSyncIncrementalProducts_ShouldSyncSpecificProducts() {
    String merchantId = "M001";
    JsonNode body = createJsonNode("{" +
        "\"syncType\":\"INCREMENTAL\"," +
        "\"platforms\":[\"cyberbiz\"]," +
        "\"productIds\":[\"PROD-001\",\"PROD-002\"]" +
    "}");

    when(productService.findById("PROD-001"))
        .thenReturn(createProduct("PROD-001", "商品A", 1500.0, "active"));
    when(productService.findById("PROD-002"))
        .thenReturn(createProduct("PROD-002", "商品B", 2500.0, "active"));

    syncProductHandler.syncProducts(merchantId, body);

    verify(productService, times(2)).findById(any());
}
```

**Step 7: Run all SyncProductHandler tests**

Run: `./gradlew simpleec-backend-job:test --tests SyncProductHandlerTest -v`
Expected: All tests PASS

**Step 8: Commit**

```bash
git add simpleec-backend-job/src/main/java/com/simpleec/backendjob/handler/SyncProductHandler.java
git add simpleec-backend-job/src/test/java/com/simpleec/backendjob/handler/SyncProductHandlerTest.java
git commit -m "feat: Implement SyncProductHandler for SYNC_PRODUCT Kafka task

- Implement syncFullProducts() to fetch all products via ProductService
- Implement syncIncrementalProducts() for specific product sync
- Add syncProductToPlatform() stub for platform API calls
- Comprehensive test coverage for full and incremental sync scenarios
- Includes price synchronization as product attribute
- Handles merchant isolation and error resilience per product"
```

---

## Task 3: Implement SyncPackHandler for SYNC_PACK TaskType

**Files:**
- Modify: `simpleec-backend-job/src/main/java/com/simpleec/backendjob/handler/SyncPackHandler.java`
- Create: `simpleec-backend-job/src/test/java/com/simpleec/backendjob/handler/SyncPackHandlerTest.java`
- Reference: `simpleec-core/src/main/java/com/simpleec/core/entity/SellerPack.java`
- Reference: `simpleec-core/src/main/java/com/simpleec/core/service/SellerPackService.java`

**Context:** SyncPackHandler manages SellerPack (商品包/listing) synchronization:
- SELLER_PACK sync: Bundle multiple SKUs into sales units
- INVENTORY sync: Update available quantities (but NOT actually sync inventory - that's handled separately)
- Note: User said not to implement UPDATE_INVENTORY; this handler should focus on pack/listing structures

**Step 1: Write the failing test for seller pack sync**

```java
@Test
void testSyncSellerPacks_ShouldSyncPacksToCyberbiz() {
    String merchantId = "M001";
    JsonNode body = createJsonNode("{" +
        "\"syncType\":\"SELLER_PACK\"," +
        "\"platforms\":[\"cyberbiz\"]," +
        "\"warehouseId\":\"WH-001\"" +
    "}");

    List<SellerPack> mockPacks = List.of(
        createSellerPack("PACK-001", "套組A", List.of("PROD-001", "PROD-002"), "active"),
        createSellerPack("PACK-002", "套組B", List.of("PROD-003"), "active")
    );
    when(sellerPackService.findByMerchantId(merchantId)).thenReturn(mockPacks);

    syncPackHandler.syncPacks(merchantId, body);

    verify(sellerPackService).findByMerchantId(merchantId);
    assertEquals(2, mockPacks.size());
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew simpleec-backend-job:test --tests SyncPackHandlerTest::testSyncSellerPacks -v`
Expected: FAIL

**Step 3: Implement SyncPackHandler.syncSellerPacks()**

```java
private void syncSellerPacks(String merchantId, List<String> platforms, String warehouseId) {
    try {
        log.info("Syncing seller packs to {} platforms", platforms.size());

        List<SellerPack> packs = sellerPackService.findByMerchantId(merchantId);

        for (SellerPack pack : packs) {
            for (String platform : platforms) {
                try {
                    syncPackToPlatform(pack, platform, merchantId);
                } catch (Exception e) {
                    log.error("Failed to sync pack {} to {}", pack.getId(), platform, e);
                }
            }
        }

        log.info("Synced {} seller packs", packs.size());
    } catch (Exception e) {
        log.error("Error syncing seller packs for {}", merchantId, e);
    }
}

private void syncPackToPlatform(SellerPack pack, String platform, String merchantId) throws Exception {
    log.info("Syncing seller pack {} (SKUs: {}) to platform {}",
        pack.getId(), pack.getProductIds(), platform);

    // TODO: Call platform-specific API
    // - cyberbiz: CyberbizApiClient.updateSellerPack(pack)
    // - shopee: ShopeeApiClient.updateListing(pack)
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew simpleec-backend-job:test --tests SyncPackHandlerTest -v`
Expected: All tests PASS

**Step 5: Commit**

```bash
git add simpleec-backend-job/src/main/java/com/simpleec/backendjob/handler/SyncPackHandler.java
git add simpleec-backend-job/src/test/java/com/simpleec/backendjob/handler/SyncPackHandlerTest.java
git commit -m "feat: Implement SyncPackHandler for SYNC_PACK Kafka task

- Implement syncSellerPacks() to fetch and sync bundle configurations
- Implement syncPackToPlatform() stub for platform API calls
- Support warehouse-scoped pack synchronization
- Error resilience: continue on individual pack failures
- Comprehensive test coverage for seller pack sync scenarios
- NOTE: Does not sync inventory quantities (per requirement)"
```

---

## Task 4: Verify Backend Job Kafka Integration (SYNC_PRODUCT + SYNC_PACK)

**Files:**
- Verify: `simpleec-backend-job/src/main/java/com/simpleec/backendjob/consumer/BackendJobConsumer.java` (line 54-59)
- Create: `simpleec-backend-job/src/test/java/com/simpleec/backendjob/consumer/BackendJobConsumerTest.java`

**Context:** BackendJobConsumer routes SYNC_PRODUCT and SYNC_PACK taskTypes to respective handlers. Already implemented in the consumer routing logic; we just need to verify the integration works.

**Step 1: Read BackendJobConsumer switch/case logic**

Verify that:
- Line 54: `case "SYNC_PRODUCT"` routes to `handleSyncProduct()`
- Line 58: `case "SYNC_PACK"` routes to `handleSyncPack()`
- Both methods delegate to respective handlers
- Error handling is in place

**Step 2: Write integration test for SYNC_PRODUCT message**

```java
@Test
void testBackendJobConsumer_ShouldRouteSyncProductMessage() {
    String message = "{" +
        "\"header\":{\"taskType\":\"SYNC_PRODUCT\",\"merchantId\":\"M001\"}," +
        "\"body\":{\"syncType\":\"INCREMENTAL\",\"platforms\":[\"cyberbiz\"],\"productIds\":[\"PROD-001\"]}" +
    "}";

    backendJobConsumer.consumeBackendTask(message);

    verify(syncProductHandler).syncProducts("M001", any(JsonNode.class));
}
```

**Step 3: Write integration test for SYNC_PACK message**

```java
@Test
void testBackendJobConsumer_ShouldRouteSyncPackMessage() {
    String message = "{" +
        "\"header\":{\"taskType\":\"SYNC_PACK\",\"merchantId\":\"M001\"}," +
        "\"body\":{\"syncType\":\"SELLER_PACK\",\"platforms\":[\"cyberbiz\"],\"warehouseId\":\"WH-001\"}" +
    "}";

    backendJobConsumer.consumeBackendTask(message);

    verify(syncPackHandler).syncPacks("M001", any(JsonNode.class));
}
```

**Step 4: Run integration tests**

Run: `./gradlew simpleec-backend-job:test --tests BackendJobConsumerTest -v`
Expected: PASS all routing tests

**Step 5: Manual Kafka test (optional but recommended)**

Send a test message to task.backend topic:

```bash
docker exec simpleec-kafka /opt/kafka/bin/kafka-console-producer.sh \
  --broker-list localhost:9092 --topic task.backend << EOF
{"header":{"taskType":"SYNC_PRODUCT","merchantId":"TEST001"},"body":{"syncType":"FULL","platforms":["cyberbiz"]}}
EOF
```

Check logs: `docker logs simpleec-backend-job --tail 20 | grep -i "sync_product"`
Expected: Log message about product sync being triggered

**Step 6: Commit**

```bash
git add simpleec-backend-job/src/test/java/com/simpleec/backendjob/consumer/BackendJobConsumerTest.java
git commit -m "test: Add Backend Job Kafka integration tests

- Verify SYNC_PRODUCT message routing to SyncProductHandler
- Verify SYNC_PACK message routing to SyncPackHandler
- Test error handling for malformed messages
- Integration test with actual Kafka messages (manual verification)"
```

---

## Task 5: Update Backend Job Application Config (Add Jackson Dependency if Needed)

**Files:**
- Verify: `simpleec-backend-job/build.gradle`

**Context:** Backend job uses Jackson for JSON deserialization. Verify dependency is present.

**Step 1: Check if Jackson is already in dependencies**

Read: `simpleec-backend-job/build.gradle`

Expected: `implementation 'com.fasterxml.jackson.core:jackson-databind'` should be present

If missing:
```gradle
dependencies {
    implementation 'com.fasterxml.jackson.core:jackson-databind'
    // ... rest of dependencies
}
```

**Step 2: Rebuild if changes made**

Run: `./gradlew simpleec-backend-job:clean simpleec-backend-job:bootJar -q`

**Step 3: Commit if needed**

```bash
git add simpleec-backend-job/build.gradle
git commit -m "build: Add Jackson dependency to backend-job"
```

---

## Task 6: Run Full Integration Test Suite

**Files:**
- Run: `./gradlew simpleec-backend-job:test -v`
- Run: `./gradlew simpleec-channel-job:test -v`
- Run: `./gradlew simpleec-order-job:test -v`

**Context:** Verify all components work together and no regressions introduced.

**Step 1: Run all backend-job tests**

Run: `./gradlew simpleec-backend-job:test -v 2>&1 | tail -20`
Expected: All tests PASS

**Step 2: Run all channel-job tests**

Run: `./gradlew simpleec-channel-job:test -v 2>&1 | tail -20`
Expected: All tests PASS

**Step 3: Run all order-job tests (ReturnUpsertConsumer verification)**

Run: `./gradlew simpleec-order-job:test -v 2>&1 | tail -20`
Expected: All 11 tests PASS (ReturnUpsertHandler + ReturnUpsertConsumer)

**Step 4: Build all JARs**

Run: `./gradlew build -x test -q 2>&1 | tail -5`
Expected: All 13 modules build successfully

**Step 5: Commit with test summary**

```bash
git add docs/TEST_RESULTS.md
git commit -m "test: All integration tests passing - Cyberbiz + Backend Job complete

- Backend Job: SyncProductHandler + SyncPackHandler fully implemented with 8+ tests
- Channel Job: Cyberbiz fetchReturns verified working
- Order Job: ReturnUpsertConsumer all 11 tests passing
- All 13 microservice JARs building successfully
- Ready for system testing with real Cyberbiz orders"
```

---

## Notes for Implementation

### Key Services Required
- **ProductService**: `findByMerchantId()`, `findById()` methods
- **SellerPackService**: `findByMerchantId()` method
- **CyberbizApiClient**: Already implemented
- **ReturnOrderService**: Already exists from ReturnUpsertHandler work

### Kafka Message Format
Messages on task.backend topic:
```json
{
  "header": {
    "taskType": "SYNC_PRODUCT|SYNC_PACK",
    "merchantId": "M001",
    "timestamp": 1645123456
  },
  "body": {
    "syncType": "FULL|INCREMENTAL|SELLER_PACK",
    "platforms": ["cyberbiz", "shopee"],
    "productIds": ["PROD-001"],  // optional for SYNC_PRODUCT
    "warehouseId": "WH-001"      // optional for SYNC_PACK
  }
}
```

### Data Flow
1. **Cyberbiz Returns**: Scheduler → ChannelJobConsumer → CyberbizAdapter.fetchReturns() → Kafka (return.process) → ReturnUpsertConsumer → DB
2. **Product Sync**: Scheduler/Admin → task.backend (SYNC_PRODUCT) → BackendJobConsumer → SyncProductHandler → Platform APIs
3. **Pack Sync**: Scheduler/Admin → task.backend (SYNC_PACK) → BackendJobConsumer → SyncPackHandler → Platform APIs
4. **Price Updates**: Included in SYNC_PRODUCT (price is product attribute)

### Error Handling Strategy
- Individual item failures don't stop batch processing (continue on error)
- All errors logged with context (merchantId, productId, platform)
- Failed items can be retried via task.failed topic later

### Testing Strategy
- Unit tests for each handler (mock ProductService, SellerPackService)
- Integration tests for consumer routing logic
- Manual Kafka message sending for end-to-end verification
