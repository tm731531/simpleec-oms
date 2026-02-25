# Cyberbiz Channel Integration — Development Experience & Lessons Learned

> 一条龙从API集成、Kafka消息、数据库持久化的完整开发纪录。
> 适用于其他平台（Shopee, Momo, Yahoo, Easystore）和退货流程的开发。

**开发周期**: 2026-02-24 至 2026-02-25（约 16 小时）
**主要成果**: ✅ 订单持久化完全解决 | ✅ 三层架构验证 | ✅ 共 6 次迭代修复

---

## 第一部分：架构设计核心

### 1. 三层职责分离（务必遵守）

```
┌──────────────┬────────────────────┬──────────────────┐
│ Scheduler    │ Channel Job        │ Consumer Handler │
├──────────────┼────────────────────┼──────────────────┤
│ WHAT（何时）  │ HOW to FETCH       │ HOW to PROCESS   │
│ 给出时间戳   │ (数据适配层)       │ (业务逻辑层)     │
├──────────────┼────────────────────┼──────────────────┤
│ timestamp:   │ • API 调用         │ • 去重检查       │
│ 2026-02-13   │ • 时间窗口计算     │ • 数据库查询     │
│ T10:00:00Z   │ • 格式转换         │ • INSERT/UPDATE  │
│             │ • 分页/游标处理    │ • 业务验证       │
│             │ • rate limit 管理  │ • 状态转移       │
│             │ • detail API 决策  │                  │
└──────────────┴────────────────────┴──────────────────┘
```

**关键原则**：
- ❌ **Scheduler 不应该**：指定 API 的时间窗口、分页策略、rate limit
- ✅ **Scheduler 只做**：传递时间戳，让 Channel 自主决策
- ❌ **Channel Job 不应该**：访问数据库、做业务验证、管理订单状态
- ✅ **Channel Job 只做**：调用 API、格式转换、发送到 Kafka

**好处**：
- 各层职责清晰，易于单元测试
- Channel 可独立迭代（无需改 Consumer 或 Scheduler）
- Consumer 可独立修复业务逻辑（无需改 Channel）

---

### 2. 统一 Header/Body 消息结构（所有 Kafka 主题通用）

```json
{
  "header": {
    "taskType": "ORDER_UPSERT|FETCH_ORDERS|SHIP_ORDER|...",
    "merchantId": "MERCHANT_001",
    "platformId": "cyberbiz|shopee|momo|yahoo|easystore",
    "channelId": "CHANNEL_CYBERBIZ_001",
    "requestId": "req_xyz",
    "timestamp": "2026-02-25T10:00:00Z",
    "source": "channel_job|api|webhook",
    "version": 1,
    "isRollback": false
  },
  "body": {
    // TaskType-specific data
    "orderData": { ... },
    "orderHash": "fb513ecb..."
  }
}
```

**Header 的作用**：
- `taskType` → Router（决定由哪个 Handler 处理）
- `isRollback` → 业务逻辑标记（回补订单的业绩/库存是否追溯原日期）
- `version` → 向后兼容（未来 API 变更时不会破坏消费）
- `timestamp` → Consumer 验证消息新鲜度

**Body 的作用**：
- 存储业务数据，对齐数据库 schema
- `orderHash` → 去重检查（同一订单多次更新时跳过）

---

### 3. isRollback 标签设计（处理历史数据回补）

```
isRollback=false (正常新订单):
  ✓ 业绩计入当日（based on channelCreatedAt）
  ✓ 库存正常扣减
  ✓ 统计数据参与排名

isRollback=true (历史数据回补):
  ✓ 业绩追溯原日期（based on channelCreatedAt，不是当前日期）
  ✓ 库存调整（可能需要特殊处理）
  ✓ 统计数据标记为回补（不参与排名）
  ✓ 触发回补专用事件
```

**适用场景**：
- 新平台接入时，补历史 30 天订单
- API 数据丢失后的重新拉取
- 通路端数据修正后的同步

---

## 第二部分：开发过程中的坑和解决方案

### 1. 坑一：API 认证（Cyberbiz 特例）

**问题**：API 调用返回 401 Unauthorized

**排查步骤**：
1. 检查 API 基础 URL：`api.cyberbiz.io` ❌ → `api.cyberbiz.co` ✅
2. 检查 HMAC 签名格式：是否包含 `request-line:` 前缀 ❌
3. 查 API 文档，对比 Content-Type、Body 序列化方式

**解决方案**：
```java
// ❌ 错误：不要加 "request-line:" 前缀
String sigStr = "x-date: ...\nrequest-line: GET /v1/orders HTTP/1.1";

// ✅ 正确：只需要方法、路径、协议版本
String sigStr = "x-date: ...\nGET /v1/orders HTTP/1.1";

// HMAC 签名：
String signature = HmacUtils.hmacSha1Hex(secret, sigStr);
```

**教训**：
- 每个平台的 API 认证都不同（OAuth、HMAC、API Key、Bearer Token...）
- 必须按文档严格实现，不能随意改格式
- 建议先用 Postman/curl 验证 API，再写代码

---

### 2. 坑二：时间戳传递错误（Channel Job 内部）

**问题**：API 调用成功但返回 0 条订单

**排查过程**：
```
❌ 旧逻辑：Scheduler 传 2026-02-17T10:00:00Z
         → Channel Job 收到后，用 Instant.now() 查询
         → 实际查询：2026-02-25T... 到 2026-02-25T...（当前时间）
         → 结果：漏掉中间 8 天的订单！

✅ 正确逻辑：Scheduler 传 2026-02-17T10:00:00Z
            → Channel Job 用这个时间戳计算时间窗口
            → 查询：2026-02-10T10:00:00Z 到 2026-02-17T10:00:00Z
            → 结果：获得完整的 7 天数据
```

**解决方案**：
```java
// 在 ChannelJobConsumer 中提取时间戳
String timestamp = message.header().get("timestamp").asText();
long baseTimestamp = Instant.parse(timestamp).getEpochSecond();

// 传递给 Channel Adapter
adapter.fetchOrderListByTimestamp(channelId, baseTimestamp);

// 在 Adapter 中使用
long createdFrom = baseTimestamp - (7 * 86400);  // 7 天前
long updatedFrom = baseTimestamp - 86400;        // 1 天前
return cyberbizApiClient.getOrders(createdFrom, baseTimestamp);
```

**教训**：
- 永远使用 Message 携带的 `timestamp`，而不是 `Instant.now()`
- Channel Job 是纯粹的"当前时间点的数据转换"，不应决定时间范围
- 如果平台没有时间范围 API（如 Yahoo 的 `updated_after`），由 Channel 负责评估

---

### 3. 坑三：消息字段对应数据库 Schema

**问题**：Consumer 无法正确映射 API 数据到数据库表

**坑的案例**：
```
❌ Channel Job 发送：{ "status": "PENDING" }
✅ Database 期望：`order_status` 字段

❌ Channel Job 发送：{ "createdAt": "..." }
✅ Database 期望：`channel_created_at` 字段（且时间格式不同）

❌ Channel Job 发送 items 为字符串
✅ Database 期望 JSONB 数组
```

**预防方案**：
1. 在 Channel Job 发送前，对照 `orders` 表 Schema：
   ```bash
   psql -U simpleec -d simpleec -c "
   SELECT column_name, data_type, is_nullable
   FROM information_schema.columns
   WHERE table_name = 'orders'
   ORDER BY ordinal_position;"
   ```

2. 在 Consumer 中添加验证日志：
   ```java
   log.info("Processing order data - fields: {}",
       String.join(", ", orderDataJson.fieldNames()));
   ```

3. 字段映射表（文档必须）：
   | API 字段 | DB 字段 | 类型 | 转换逻辑 |
   |---------|--------|------|---------|
   | status | order_status | VARCHAR | Enum.fromCode(status) |
   | createdAt | channel_created_at | TIMESTAMP | Instant.parse(createdAtStr).atZone(UTC).toLocalDateTime() |
   | items | items | JSONB | objectMapper.writeValueAsString(itemsNode) |

---

### 4. 坑四：Hibernate NOT NULL 验证 vs DB DEFAULT 约束

**问题**：订单无法入库，Hibernate 报错
```
PropertyValueException: not-null property references a null or transient value: discountAmount
```

**根本原因**（关键！）：
```
Hibernate 6.6 的行为：
1. 验证阶段：检查所有 @Column(nullable=false) 的字段不能为 null
   ↓ 如果为 null，抛异常（不继续）
2. INSERT 语句生成：包含这些字段（值为 null）
3. DB 层面：使用 DEFAULT 约束

❌ 问题：第 1 步失败，永远无法到达第 3 步！

✅ 解决：跳过第 1、2 步，让 DB 负责
@Column(name="discount_amount", nullable=false, insertable=false, updatable=true)
↓
Hibernate 行为改变：
1. 验证阶段：检查（但 insertable=false 意味着"这个字段 DB 会处理"）
2. INSERT 语句：不包含这个字段
3. DB 层面：自动应用 DEFAULT 约束
```

**详细解决方案**：

```java
// 在 Order Entity 中
@Column(name = "total_amount", nullable = false, insertable = false, updatable = true)
@ColumnDefault("0")
private BigDecimal totalAmount;

@Column(name = "shipping_fee", nullable = false, insertable = false, updatable = true)
@ColumnDefault("0")
private BigDecimal shippingFee;

@Column(name = "discount_amount", nullable = false, insertable = false, updatable = true)
@ColumnDefault("0")
private BigDecimal discountAmount;

@Column(name = "items", columnDefinition = "jsonb", nullable = false, insertable = false, updatable = true)
@JdbcTypeCode(SqlTypes.JSON)
@ColumnDefault("'[]'::jsonb")
private String items;
```

**参数说明**：
- `nullable = false`：DB 约束层面
- `insertable = false`：**关键** — Hibernate INSERT 时跳过这个字段
- `updatable = true`：UPDATE 时仍可更新（如果提供新值）
- `@ColumnDefault("0")`：告诉 Hibernate DDL 生成器，以及文档记录

**验证**：
```bash
# 生成的 INSERT 语句
INSERT INTO orders (id, merchant_id, channel_id, channel_order_id, order_status, buyer_name, ...)
VALUES (?, ?, ?, ?, ?, ?, ...)
-- 注意：total_amount, shipping_fee, discount_amount, items 不在列表中！

# PostgreSQL 自动应用 DEFAULT
INSERT INTO orders (...) VALUES (...)
-- total_amount 默认 0
-- shipping_fee 默认 0
-- discount_amount 默认 0
-- items 默认 '[]'::jsonb
```

**教训**：
- Hibernate 与 DB DEFAULT 的互动复杂，记住这个解决方案
- 对于可选字段（API 可能不提供）+ 有 DEFAULT 的字段，必须用 `insertable=false`
- 不要依赖 Consumer 明确设置这些值（Consumer 不应该关心默认值逻辑）

---

## 第三部分：开发清单（用于新平台集成）

### Phase 1: API 集成（1-2 天）

- [ ] **API 文档阅读**
  - [ ] 认证方式（OAuth/HMAC/API Key/JWT）
  - [ ] 订单列表 API 端点、参数、分页方式
  - [ ] 订单详情 API 是否存在（Shopee 有，Momo 没有）
  - [ ] 时间参数名（Shopee: `create_time_from/to`, Yahoo: `updated_after`）
  - [ ] 返回字段（items 在订单顶层还是需要额外 API）

- [ ] **Adapter 实现**（继承 `ChannelAdapter`）
  ```java
  @Component
  public class NewPlatformAdapter extends ChannelAdapter {
      @Override
      public List<String> fetchOrderListByTimestamp(String channelId, long baseTimestamp) {
          // 根据 baseTimestamp 计算时间窗口（此为 Channel 的决策权）
          // 实现逻辑：
          // 1. 建立 API 连接（HTTP client / OAuth token）
          // 2. 计算时间窗口（如 7 天前 ~ 当前）
          // 3. 循环调用 API（处理分页）
          // 4. 格式转换成统一结构
          // 5. 返回 channelOrderId 列表
      }
  }
  ```

- [ ] **本地测试**
  ```bash
  # 用 Postman/curl 验证 API
  curl -X GET "https://api.platform.com/v1/orders?..." \
       -H "Authorization: Bearer ..." \
       -H "Content-Type: application/json"
  # 检查返回数据格式、分页参数、时间参数
  ```

---

### Phase 2: Kafka 消息结构设计（1 天）

- [ ] **定义 Body 结构**
  ```java
  // 对照 orders 表 Schema，确定每个字段的来源
  {
    "orderData": {
      "orderId": "OMS 自己生成的 NanoID",
      "channelOrderId": "平台订单号",
      "orderStatus": "PENDING/CONFIRMED/SHIPPED/COMPLETED",
      "totalAmount": 100.00,
      "shippingFee": 10.00,
      "discountAmount": 5.00,
      "channelCreatedAt": "2026-02-25T10:00:00Z",  // ISO-8601
      "paidAt": "2026-02-25T10:05:00Z",            // nullable
      "shippedAt": null,
      "items": [
        {
          "channelItemId": "item_123",
          "productName": "...",
          "quantity": 2,
          "price": 50.00
        }
      ],
      "buyerInfo": { "name": "...", "email": "...", ... },
      "shippingInfo": { "address": "...", "method": "...", ... },
      "paymentMethod": "CREDIT_CARD",
      "shippingMethod": "EXPRESS"
    },
    "orderHash": "SHA256(以上所有字段)"
  }
  ```

- [ ] **在 Handler 中填充 orderData**
  ```java
  // ModeB（Cyberbiz, Shopee）：Order Detail API 有完整数据
  // ModeA（Shopify, Easystore）：需要逐个调用 detail API
  private JsonNode buildOmsOrderData(JsonNode apiOrder) {
      ObjectNode orderData = mapper.createObjectNode();
      orderData.put("orderId", NanoIdUtil.generateComposite(merchantId));
      orderData.put("channelOrderId", apiOrder.get("order_id").asText());
      // ... 逐个字段映射
      orderData.set("items", serializeItems(apiOrder.get("items")));
      return orderData;
  }
  ```

---

### Phase 3: Consumer 实现（1 天）

- [ ] **订单入库逻辑**（OrderUpsertConsumer）
  ```java
  private void handleOrderUpsert(...) {
      // 1. Redis 去重（快速判定）
      // 2. DB 查询（确认是否存在）
      // 3. CREATE 或 UPDATE
      // 4. 验证（日志记录是否真的入库）
      // 5. 更新 Redis 缓存（7 天 TTL）
  }
  ```

- [ ] **字段映射**（确保与 Entity 对应）
  ```java
  // 在 populateOrderFromData() 中处理每个字段
  if (orderDataJson.has("totalAmount")) {
      order.setTotalAmount(new BigDecimal(orderDataJson.get("totalAmount").asText()));
  }
  // 注意：discountAmount 等字段可以不提供（Hibernate insertable=false）
  ```

- [ ] **错误处理**
  ```java
  // 可重试（网络临时故障）→ task.failed topic (保留 1 天)
  // 不可重试（数据格式错误）→ task.dlt topic (保留 30 天)
  ```

---

### Phase 4: 端到端测试（1 天）

- [ ] **单元测试**（不依赖真实 API）
  ```java
  @Test
  public void testOrderUpsert_ShouldPersistToDatabase() {
      // 模拟 Kafka 消息
      // 调用 Consumer
      // 验证数据库中的订单
  }
  ```

- [ ] **集成测试**（依赖 Docker 容器）
  ```bash
  # 启动所有容器
  docker compose up -d

  # 发送测试消息到 Kafka
  docker exec simpleec-kafka kafka-console-producer.sh \
    --broker-list kafka:9092 \
    --topic order.process \
    < test_message.json

  # 等待 Consumer 处理
  sleep 5

  # 验证数据库
  docker exec simpleec-postgres psql -U simpleec -d simpleec -c \
    "SELECT * FROM orders WHERE channel_order_id = 'TEST_123';"
  ```

- [ ] **生产验证**
  ```bash
  # 查看 Consumer 日志中的验证消息
  docker logs simpleec-order-job 2>&1 | grep "✓ VERIFIED"

  # 查看是否有错误
  docker logs simpleec-order-job 2>&1 | grep "✗ FAILED"
  ```

---

## 第四部分：退货流程开发（Return Orders）

基于上述经验，开发退货流程的要点：

### 1. 消息结构扩展

```json
{
  "header": {
    "taskType": "FETCH_RETURNS|FETCH_RETURN_DETAIL|PROCESS_RETURN|APPROVE_RETURN",
    "isRollback": false
  },
  "body": {
    "returnData": {
      "returnId": "RET_...",
      "channelReturnId": "平台退货号",
      "returnStatus": "PENDING/APPROVED/REJECTED/SHIPPED",
      "orderId": "原订单 ID",
      "returnAmount": 100.00,
      "returnReason": "...",
      "items": [{ "channelItemId": "...", "quantity": 1 }],
      "returnedAt": "2026-02-26T10:00:00Z"
    }
  }
}
```

### 2. 三层职责（参考订单）

- **Scheduler**: 发送时间戳，触发"拉取最近 7 天的退货"
- **Channel Job**: 调用平台 API，格式转换，发送到 `return.process` topic
- **Consumer** (ReturnUpsertConsumer):
  - 去重、DB 查询、INSERT/UPDATE
  - 关联原订单（`orders.id`）
  - 触发库存调整、积分返还、业绩修正

### 3. 特殊处理

- **isRollback** 对退货同样适用（历史退货数据回补时标记）
- **退货状态转移**: 接受任何平台返回的状态（不验证转移逻辑）
- **库存调整**: 由后台 JOB (backend-job) 处理，不由 Consumer 直接操作

---

## 第五部分：常用命令速查

```bash
# 1. 快速重启单个服务
./quick-redeploy.sh simpleec-order-job

# 2. 查看 Kafka topic
docker exec simpleec-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka:9092 --list

# 3. 读取 Kafka 消息
docker exec simpleec-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:9092 \
  --topic order.process \
  --from-beginning \
  --max-messages 5

# 4. 查看 Consumer Group 状态
docker exec simpleec-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:9092 \
  --group order-job-group \
  --describe

# 5. 查询数据库
docker exec simpleec-postgres psql -U simpleec -d simpleec -c \
  "SELECT id, channel_order_id, order_status, total_amount FROM orders LIMIT 5;"

# 6. 查看订单 Job 日志
docker logs simpleec-order-job 2>&1 | grep -E "Processing|VERIFIED|ERROR"
```

---

## 第六部分：架构检查清单

每次开发新平台或功能时，确保：

- [ ] **三层职责清晰分离**
  - [ ] Scheduler: 只传时间戳
  - [ ] Channel Job: 不访问 DB，不验证业务规则
  - [ ] Consumer: 不调用外部 API，专注数据持久化

- [ ] **消息结构完整**
  - [ ] Header 包含：taskType, merchantId, platformId, timestamp, version, isRollback
  - [ ] Body 对齐数据库 schema
  - [ ] 所有字段有清晰的来源和转换逻辑

- [ ] **Kafka 消息格式统一**（无论 order/return/product）
  - [ ] 使用 Header/Body 结构
  - [ ] taskType 明确路由
  - [ ] version 支持向后兼容

- [ ] **数据库持久化安全**
  - [ ] Entity 的可选字段用 `insertable=false`
  - [ ] 去重逻辑（orderHash）已实现
  - [ ] 验证日志（✓ VERIFIED）已添加

- [ ] **错误处理完善**
  - [ ] 可重试错误 → task.failed (1 天)
  - [ ] 不可重试错误 → task.dlt (30 天)
  - [ ] 日志清晰（便于问题排查）

- [ ] **测试覆盖**
  - [ ] 本地 Postman API 测试
  - [ ] Kafka 消息格式验证
  - [ ] E2E 集成测试
  - [ ] 日志验证

---

## 总结

这次 Cyberbiz 集成的关键收获：

1. **API 认证没有标准** — 每个平台都不同，必须严格按文档
2. **时间戳管理是关键** — Scheduler 传时间，Channel 决策窗口，不要用 `Instant.now()`
3. **消息字段与 DB schema 的对应** — 提前列出映射表，减少入库错误
4. **Hibernate + PostgreSQL 的坑** — `insertable=false` 是解决 NOT NULL + DEFAULT 的标准答案
5. **三层职责分离是架构的灵魂** — 遵守这个原则，新平台集成会非常快

后续 Shopee, Momo, Yahoo, Easystore 和退货流程可以完全复用这套模式。

