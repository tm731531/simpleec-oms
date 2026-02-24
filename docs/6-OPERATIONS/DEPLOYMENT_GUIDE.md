# SimpleEC OMS 部署指南

> 用於本地開發、測試環境和生產部署

---

## 1. 本地開發環境設置

### 1.1 系統要求

```
JDK 17+              (LTS)
Maven 3.8+
Docker 20.10+        (for Testcontainers)
PostgreSQL 14+       (local or Docker)
Apache Kafka 3.0+    (local or Docker)
Git 2.30+
```

### 1.2 初始化步驟

```bash
# 1. Clone 代碼庫
git clone https://github.com/tm731531/simpleec-oms.git
cd simpleec-oms

# 2. 切換到 implementation 分支
git checkout implementation

# 3. 啟動基礎設施（PostgreSQL + Kafka）
docker-compose -f docker/docker-compose.dev.yml up -d

# 驗證啟動狀態
docker ps
# 應該看到 postgres, kafka, zookeeper 容器運行中

# 4. 等待服務就緒（30 秒左右）
sleep 30

# 5. 建構項目
mvn clean install

# 6. 運行測試
mvn test

# 7. 啟動應用
mvn spring-boot:run -pl simpleec-oms-app
```

### 1.3 開發工具建議

```
IDE:
  - IntelliJ IDEA Community Edition
    或 VS Code + Java Extension Pack

Plugins:
  - Lombok
  - SonarLint
  - Docker

其他:
  - Postman (API 測試)
  - DBeaver (DB 管理)
  - Kafka Tools (消息監控)
```

---

## 2. Docker Compose 配置

### 2.1 開發環境 (docker-compose.dev.yml)

```yaml
version: '3.8'

services:
  postgres:
    image: postgres:14-alpine
    environment:
      POSTGRES_DB: simpleec_oms_dev
      POSTGRES_USER: oms_user
      POSTGRES_PASSWORD: dev_password
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U oms_user"]
      interval: 10s
      timeout: 5s
      retries: 5

  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
    ports:
      - "2181:2181"

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    depends_on:
      - zookeeper
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
    ports:
      - "9092:9092"
    healthcheck:
      test: kafka-broker-api-versions.sh --bootstrap-server localhost:9092
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  postgres_data:
```

### 2.2 建立 Kafka Topics

```bash
# 進入 Kafka 容器
docker exec -it kafka bash

# 建立 topics
kafka-topics.sh --create \
  --bootstrap-server localhost:9092 \
  --topic order.process \
  --partitions 3 \
  --replication-factor 1

kafka-topics.sh --create \
  --bootstrap-server localhost:9092 \
  --topic return.process \
  --partitions 3 \
  --replication-factor 1

kafka-topics.sh --create \
  --bootstrap-server localhost:9092 \
  --topic task.failed \
  --partitions 1 \
  --replication-factor 1

# 驗證 topics
kafka-topics.sh --list --bootstrap-server localhost:9092
```

---

## 3. 資料庫初始化

### 3.1 Flyway 遷移

```
src/main/resources/db/migration/
├─ V1__init_schema.sql        (create tables)
├─ V2__add_indexes.sql        (add indexes)
└─ V3__insert_defaults.sql    (default data)
```

自動執行（Spring Boot 啟動時）：
```yaml
# application.yml
spring:
  flyway:
    locations: classpath:db/migration
    baseline-on-migrate: true
```

### 3.2 手動初始化（如需）

```bash
# 連接 PostgreSQL
psql -h localhost -U oms_user -d simpleec_oms_dev

# 執行初始化腳本
\i docker/init-db/01-schema.sql
\i docker/init-db/02-channel-shipping-mapping.sql

# 驗證表格
\dt

# 退出
\q
```

---

## 4. 應用配置

### 4.1 application.yml 配置

```yaml
spring:
  application:
    name: simpleec-oms
  datasource:
    url: jdbc:postgresql://localhost:5432/simpleec_oms_dev
    username: oms_user
    password: dev_password
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQL14Dialect
        format_sql: true
        use_sql_comments: true
    show-sql: false

  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: simpleec-oms-group
      auto-offset-reset: earliest
      max-poll-records: 100
    producer:
      acks: all
      retries: 3

logging:
  level:
    com.simpleec.oms: DEBUG
    org.springframework.kafka: INFO
    org.hibernate: WARN

app:
  kafka:
    topics:
      order-process: order.process
      return-process: return.process
      task-failed: task.failed
  adapter:
    momo:
      api-key: ${MOMO_SANDBOX_API_KEY:dummy}
      sandbox: true
    shopee:
      api-key: ${SHOPEE_SANDBOX_API_KEY:dummy}
      sandbox: true
```

### 4.2 環境變數

```bash
# .env.local（本地開發，永遠別 commit）
MOMO_SANDBOX_API_KEY=momo_sandbox_key_xxx
SHOPEE_SANDBOX_API_KEY=shopee_sandbox_key_yyy

# 啟動應用時
export $(cat .env.local | xargs)
mvn spring-boot:run -pl simpleec-oms-app
```

---

## 5. 運行應用

### 5.1 開發模式

```bash
# 方式 1：Maven
mvn spring-boot:run -pl simpleec-oms-app

# 方式 2：Java
mvn clean package -pl simpleec-oms-app -DskipTests
java -jar simpleec-oms-app/target/simpleec-oms-app-*.jar

# 方式 3：IDE（推薦）
# 直接在 IntelliJ 中點擊「Run」
```

### 5.2 驗證應用啟動

```bash
# 檢查日誌
# 應該看到：
# - Kafka consumer 已啟動
# - Flyway 遷移完成
# - 準備接收消息

# 查詢應用狀態
curl http://localhost:8080/actuator/health

# 預期回應：
# {"status":"UP"}
```

---

## 6. 本地測試

### 6.1 運行所有測試

```bash
# 單元測試
mvn test -Dgroups=unit

# Integration 測試（Testcontainers）
mvn test -Dgroups=integration

# 全部測試
mvn test

# 生成覆蓋率報告
mvn jacoco:report
# 報告位置：target/site/jacoco/index.html
```

### 6.2 手動測試（發送 Kafka 消息）

```bash
# 進入 Kafka 容器
docker exec -it kafka bash

# 發送測試消息
echo '{
  "header": {
    "taskType": "PROCESS_ORDER",
    "merchantId": "M001",
    "channelId": "ch_momo",
    "requestId": "test-001",
    "timestamp": "2026-02-20T10:00:00Z"
  },
  "body": {
    "orderData": {
      "orderId": "ord_test001",
      "channelOrderId": "MOMO-TEST-001",
      "buyerName": "Test User",
      ...
    }
  }
}' | kafka-console-producer.sh \
  --broker-list localhost:9092 \
  --topic order.process

# 監聽消息
kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic order.process \
  --from-beginning
```

---

## 7. 測試環境部署

### 7.1 構建 Docker 鏡像

```dockerfile
# Dockerfile
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

COPY simpleec-oms-app/target/simpleec-oms-app-*.jar app.jar

ENV JAVA_OPTS="-Xms256m -Xmx512m"

EXPOSE 8080

ENTRYPOINT [ "sh", "-c", "java $JAVA_OPTS -jar app.jar" ]
```

### 7.2 構建與推送

```bash
# 構建鏡像
docker build -t simpleec/oms:v1.0.0 .

# 標籤化
docker tag simpleec/oms:v1.0.0 simpleec/oms:latest

# 推送到 Registry
docker push simpleec/oms:v1.0.0

# 驗證
docker pull simpleec/oms:v1.0.0
docker run --rm simpleec/oms:v1.0.0 java -version
```

### 7.3 Kubernetes 部署（可選）

```yaml
# k8s/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: simpleec-oms
spec:
  replicas: 2
  selector:
    matchLabels:
      app: simpleec-oms
  template:
    metadata:
      labels:
        app: simpleec-oms
    spec:
      containers:
      - name: oms
        image: simpleec/oms:v1.0.0
        ports:
        - containerPort: 8080
        env:
        - name: SPRING_DATASOURCE_URL
          value: jdbc:postgresql://postgres-service:5432/simpleec_oms
        - name: SPRING_KAFKA_BOOTSTRAP_SERVERS
          value: kafka-service:9092
        livenessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 20
          periodSeconds: 5
```

部署：
```bash
kubectl apply -f k8s/deployment.yaml
kubectl get pods
kubectl logs deployment/simpleec-oms
```

---

## 8. 生產部署

### 8.1 生產環境檢查清單

```
□ 代碼質量
  □ 所有測試通過
  □ 代碼覆蓋率 > 80%
  □ 無 blocker 級別的 bugs
  □ Security scan 通過

□ 性能
  □ 負載測試通過 (1000 ord/min)
  □ 內存佔用 < 512MB
  □ P99 延遲 < 100ms

□ 架構
  □ 所有依賴已固定版本
  □ Kafka topics 已建立
  □ 資料庫備份策略定義
  □ Monitoring/Alerting 配置完成

□ 文檔
  □ 部署手冊完成
  □ 故障排查指南完成
  □ API 文檔完整
  □ Runbook 準備好

□ 監控
  □ Prometheus metrics 配置
  □ Grafana 儀表板就緒
  □ Alerting rules 定義
  □ Log aggregation (ELK/Loki) 就緒
```

### 8.2 生產環境配置

```yaml
# application-prod.yml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST}:5432/simpleec_oms
    username: ${DB_USER}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS}
    producer:
      acks: all
      retries: 3

logging:
  level:
    root: WARN
    com.simpleec.oms: INFO
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"

app:
  kafka:
    max-poll-records: 500  # 生產更高的吞吐量
  adapter:
    momo:
      sandbox: false       # 連接真實 API
      api-key: ${MOMO_API_KEY}
      timeout: 10000
```

### 8.3 啟動生產環境

```bash
# 使用 systemd service
sudo systemctl start simpleec-oms

# 或使用容器編排
docker run -d \
  --name simpleec-oms \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e DB_HOST=postgres.prod \
  -e DB_USER=oms_prod \
  -e DB_PASSWORD=... \
  -e KAFKA_BOOTSTRAP_SERVERS=kafka.prod:9092 \
  -e MOMO_API_KEY=... \
  -p 8080:8080 \
  simpleec/oms:v1.0.0
```

### 8.4 監控與告警

```yaml
# prometheus-rules.yml
groups:
- name: simpleec-oms
  rules:
  - alert: OmsHandlerErrorRate
    expr: rate(oms_handler_errors_total[5m]) > 0.01
    for: 5m
    annotations:
      summary: "OMS handler error rate > 1%"

  - alert: OmsKafkaLag
    expr: oms_kafka_consumer_lag > 1000
    for: 10m
    annotations:
      summary: "OMS Kafka consumer lagging"

  - alert: OmsDbConnectionPoolExhausted
    expr: spring_db_hikari_connections_active > 18
    for: 5m
    annotations:
      summary: "OMS DB connection pool nearly exhausted"
```

---

## 9. 故障排查

### 9.1 常見問題

| 問題 | 症狀 | 解決方案 |
|------|------|---------|
| **Kafka 連接失敗** | `Connection refused: localhost:9092` | 檢查 Kafka 容器狀態；重啟 `docker-compose restart kafka` |
| **資料庫遷移失敗** | `Flyway migration failed` | 檢查 schema.sql 語法；清理 schema_version 表 |
| **Handler 超時** | 消息未被處理 | 增加 `max.poll.interval.ms`；檢查 Handler 邏輯 |
| **物流映射為 null** | 訂單建立但 logistics_company 為 null | 確認 channel_shipping_mapping 表有記錄 |

### 9.2 調試指令

```bash
# 1. 檢查容器
docker-compose ps

# 2. 查看日誌
docker-compose logs -f kafka
docker-compose logs -f postgres

# 3. 連接資料庫
docker exec -it postgres psql -U oms_user -d simpleec_oms_dev

# 4. 檢查 Kafka topics
docker exec kafka kafka-topics.sh --list --bootstrap-server localhost:9092

# 5. 監控消費者進度
docker exec kafka kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group simpleec-oms-group \
  --describe
```

---

## 10. CI/CD 流程

### 10.1 GitHub Actions 工作流

```yaml
# .github/workflows/build-test-deploy.yml
name: Build, Test, Deploy

on:
  push:
    branches: [implementation, main]
  pull_request:
    branches: [implementation]

jobs:
  build-test:
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v2
    - name: Set up JDK 17
      uses: actions/setup-java@v2
      with:
        java-version: '17'
    - name: Run tests
      run: mvn test
    - name: Build
      run: mvn clean package -DskipTests
    - name: Push Docker image
      if: success()
      run: |
        docker build -t simpleec/oms:${{ github.sha }} .
        docker push simpleec/oms:${{ github.sha }}
    - name: Deploy to staging
      if: github.ref == 'refs/heads/implementation'
      run: |
        # Deployment script
```

### 10.2 發佈流程

```bash
# 1. 確保所有測試通過
mvn test

# 2. 更新版本
mvn versions:set -DnewVersion=1.0.0

# 3. 標籤化
git tag v1.0.0
git push origin v1.0.0

# 4. 構建並推送
docker build -t simpleec/oms:v1.0.0 .
docker push simpleec/oms:v1.0.0

# 5. 部署到生產
kubectl set image deployment/simpleec-oms \
  oms=simpleec/oms:v1.0.0
```

---

**文件日期**：2026-02-20
**狀態**：MVP 部署就緒
