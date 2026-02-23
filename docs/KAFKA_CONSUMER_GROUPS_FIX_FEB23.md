# Kafka Consumer Groups 修復 - Feb 23, 2026

## 問題摘要

**症狀**: KafkaUI 和 CLI 工具都顯示零個 consumer groups，儘管應用日誌顯示消費者正在訂閱 Kafka 主題。

**影響**: 事件流架構無法運行，所有 6 個 Job 服務無法處理 Kafka 消息。

## 根本原因分析

### 1. ServiceAutoConfiguration 自動加載 (Java配置)

**問題**: `simpleec-core` 的 `AutoConfiguration.imports` 自動加載 `ServiceAutoConfiguration`
```
com.simpleec.core.config.ServiceAutoConfiguration
com.simpleec.core.config.KafkaConfig
```

`ServiceAutoConfiguration` 掃描並實例化 `OrderService`：
```java
@ComponentScan(basePackages = "com.simpleec.core.service")
public class ServiceAutoConfiguration { }
```

**影響**: 非數據庫 Job（ChannelJob, RetryJob, BackendJob等）依賴 OrderService 啟動失敗

### 2. Hardcoded localhost:9092 (Docker網絡)

**問題**: 所有 `application.yml` 有：
```yaml
kafka:
  bootstrap-servers: localhost:9092
```

在 Docker 容器中，應該使用 Docker DNS 名稱：
```yaml
kafka:
  bootstrap-servers: simpleec-kafka:9092
```

**影響**: 容器無法連接到 Kafka broker（連接被拒絕）

### 3. FrontendJob 缺少 DataSourceAutoConfiguration 排除

**問題**: FrontendJob 不需要數據庫，但 Spring Boot 自動配置試圖建立 DataSource

**錯誤信息**:
```
Failed to configure a DataSource: 'url' attribute is not specified
and no embedded datasource could be configured.
```

### 4. __consumer_offsets 主題缺失 (Kafka Broker)

**關鍵問題**: Kafka 內部主題 `__consumer_offsets` 不存在

**症狀**:
- Kafka broker 不斷嘗試自動創建：`Sent auto-creation request for Set(__consumer_offsets)`
- Consumer groups 無法註冊（coordinator 超時）
- `kafka-consumer-groups.sh --list` 返回空
- CLI describe 命令超時：`Timed out waiting for a node assignment`

**根本原因**: Kafka 無法存儲 consumer offset，broker 的 group coordinator 無法追蹤 consumer groups

## 修復方案

### 1. Java 應用層修復

#### ChannelJobApplication
```java
@SpringBootApplication(
    scanBasePackages = {
        "com.simpleec.channeljob",
        "com.simpleec.channel",
        "com.simpleec.common"
    },
    exclude = {
        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
        org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
        com.simpleec.core.config.ServiceAutoConfiguration.class
    }
)
@Import(KafkaConfig.class)
public class ChannelJobApplication { }
```

#### RetryJobApplication
```java
@SpringBootApplication(
    scanBasePackages = {
        "com.simpleec.retryjob",
        "com.simpleec.common"
    },
    exclude = {
        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
        org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
        com.simpleec.core.config.ServiceAutoConfiguration.class
    }
)
@EnableKafka
public class RetryJobApplication { }
```

#### BackendJobApplication
```java
@SpringBootApplication(
    scanBasePackages = {
        "com.simpleec.backendjob",
        "com.simpleec.common"
    },
    exclude = {
        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
        org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
        com.simpleec.core.config.ServiceAutoConfiguration.class
    }
)
@EnableKafka
public class BackendJobApplication { }
```

#### FrontendJobApplication
```java
@SpringBootApplication(
    scanBasePackages = {"com.simpleec.common", "com.simpleec.frontendjob"},
    exclude = {
        RedisRepositoriesAutoConfiguration.class,
        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
        com.simpleec.core.config.ServiceAutoConfiguration.class
    }
)
@EnableKafka
@EnableScheduling
public class FrontendJobApplication { }
```

#### SchedulerJobApplication
```java
@SpringBootApplication(
    scanBasePackages = {"com.simpleec.common", "com.simpleec.schedulerjob"},
    exclude = {
        RedisRepositoriesAutoConfiguration.class,
        com.simpleec.core.config.ServiceAutoConfiguration.class
    }
)
public class SchedulerJobApplication { }
```

### 2. AutoConfiguration 修復

**文件**: `simpleec-core/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

**修改前**:
```
com.simpleec.core.config.ServiceAutoConfiguration
com.simpleec.core.config.KafkaConfig
```

**修改後**:
```
com.simpleec.core.config.ServiceAutoConfiguration
```

**理由**: KafkaConfig 應由各 Job 應用程式明確管理（@Import 或自己的配置）

### 3. Docker 網絡配置修復

**文件**: 所有 Job 的 `application.yml`

```yaml
# 修改前
kafka:
  bootstrap-servers: localhost:9092

# 修改後
kafka:
  bootstrap-servers: simpleec-kafka:9092
```

**受影響的 Job**:
- simpleec-channel-job
- simpleec-retry-job
- simpleec-backend-job
- simpleec-frontend-job
- simpleec-scheduler-job

### 4. Kafka Broker 修復

**命令**: 手動創建 `__consumer_offsets` 主題

```bash
docker exec simpleec-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create --topic __consumer_offsets \
  --partitions 50 \
  --replication-factor 1 \
  --config cleanup.policy=compact \
  --config compression.type=producer
```

**參數解釋**:
- `--partitions 50`: 足夠多的分區以支持多個 consumer groups
- `--replication-factor 1`: 單節點 Kafka（生產環境應為 3+）
- `cleanup.policy=compact`: 日誌壓縮（Kafka 預設用於此主題）
- `compression.type=producer`: 保留生產者的壓縮設置

## 驗證步驟

### 1. 確認 Jobs 已啟動

```bash
docker compose ps | grep job

# 預期輸出: 6 個 Job 容器都應為 "Up"
```

### 2. 確認 Consumer Groups 已註冊

```bash
docker exec simpleec-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --list | sort

# 預期輸出:
# backend-consumer-group
# channel-job-group
# channel-job-pchome-fast
# channel-job-pchome-slow
# channel-job-shopee-fast
# channel-job-shopee-slow
# channel-job-yahoo-fast
# dlt-consumer-group
# frontend-job-group
# retry-job-group
# scheduler-dispatcher-group-v3
```

### 3. 檢查特定 Consumer Group

```bash
docker exec simpleec-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group channel-job-group

# 預期: 顯示分配的分區、消費者 ID、當前 offset 等
```

### 4. 檢查應用日誌

```bash
# ChannelJob 應該顯示:
docker logs simpleec-channel-job | grep "Subscribed to topic"

# 應該看到類似:
# [Consumer clientId=consumer-channel-job-group-1, groupId=channel-job-group]
# Subscribed to topic(s): cyberbiz.slow
```

## 修複前後對比

### 修復前
```
✗ KafkaUI 顯示: 0 consumer groups
✗ CLI list 返回: 空白
✗ CLI describe 返回: Timeout
✗ 應用日誌: [Consumer ...] Subscribed 但無法提交 offset
✗ 消息處理: 停止（無 offset 管理）
```

### 修復後
```
✓ KafkaUI 顯示: 11 consumer groups
✓ CLI list 返回: 所有 11 個 groups
✓ CLI describe 返回: 完整的分區分配和 offset
✓ 應用日誌: [Consumer ...] Subscribed + Node 連接成功
✓ 消息處理: 正常運行（offset 正確存儲）
```

## Git 提交

**Commit**: `8fad1cd`
**Branch**: `ops/production`
**Files Changed**: 12 files (+56, -23)

```
git log --oneline -1
8fad1cd Fix Kafka consumer groups - restore event stream architecture
```

## 部署步驟

### 1. 更新應用代碼
```bash
git pull origin ops/production
```

### 2. 重建 Docker 鏡像
```bash
docker compose build --no-cache
```

### 3. 創建 Kafka __consumer_offsets 主題
```bash
docker exec simpleec-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create --topic __consumer_offsets \
  --partitions 50 --replication-factor 1 \
  --config cleanup.policy=compact \
  --config compression.type=producer
```

### 4. 重啟所有服務
```bash
docker compose down
docker compose up -d
```

### 5. 驗證
```bash
docker exec simpleec-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --list
```

## 常見問題排查

### Q: KafkaUI 仍然顯示零個 groups？

A: 這可能是 UI 的緩存問題。嘗試：
```bash
docker compose restart nginx  # 或相關的 KafkaUI 容器
```

或直接使用 CLI 驗證（不依賴 UI）。

### Q: Consumer groups 存在但無法消費消息？

A: 檢查：
1. 主題存在：`kafka-topics.sh --list`
2. Consumer 日誌中是否有錯誤
3. Kafka broker 日誌：`docker logs simpleec-kafka`

### Q: "Timeout waiting for a node assignment" 錯誤？

A: 這表示 `__consumer_offsets` 主題仍有問題。確保：
1. 主題已創建：`kafka-topics.sh --describe --topic __consumer_offsets`
2. 主題有分區且健康
3. Kafka broker 已完全啟動

### Q: 某個 Job 仍然無法連接到 Kafka？

A: 檢查：
1. `application.yml` 中的 `bootstrap-servers` 是否為 `simpleec-kafka:9092`
2. Docker network：`docker network inspect simpleec-oms_default`
3. 容器日誌中的連接錯誤

## 性能影響

- **消息延遲**: 無顯著影響（offset 管理開銷極小）
- **CPU 使用**: 無增加（只是元數據存儲）
- **磁盤使用**: `__consumer_offsets` 主題佔用 ~100MB（log compacted）
- **網絡**: 無額外負擔

## 安全考量

- Consumer groups 沒有內置的 ACL 支援（需要 Kafka 安全配置）
- `__consumer_offsets` 主題對所有使用者可見
- 生產環境應在 Kafka broker 級別啟用身份驗證和授權

## 參考資源

- [Kafka Consumer Groups 文檔](https://kafka.apache.org/documentation/#consumerconfigs)
- [__consumer_offsets 主題](https://kafka.apache.org/documentation/#brokerconfigs)
- [Spring Kafka 配置](https://docs.spring.io/spring-kafka/docs/current/reference/html/)