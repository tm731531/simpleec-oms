# SimpleEC OMS - 网络访问指南

> 从 192.168 网络访问系统的完整指南

**系统状态**: ✅ 全部运行中
**主机 IP**: `192.168.0.48`
**日期**: 2026-02-20

---

## 🌐 Web 服务（在浏览器中打开）

### 核心服务

#### 1. **Kafka UI** - 消息队列管理
- **URL**: http://192.168.0.48:8088
- **用途**: 查看 Kafka topics、messages、consumer groups
- **功能**: 实时监控订单消息流
- **访问**: 无需登录

#### 2. **Grafana** - 监控仪表板
- **URL**: http://192.168.0.48:3000
- **用户名**: admin
- **密码**: admin
- **用途**: 查看系统性能指标、订单处理速率、延迟等
- **功能**: 实时监控系统健康状态

#### 3. **Prometheus** - 指标数据库
- **URL**: http://192.168.0.48:9090
- **用途**: 查询系统指标
- **功能**: 支持 PromQL 查询、告警规则

#### 4. **API 服务**
- **URL**: http://192.168.0.48:8082
- **用途**: REST API 端点
- **功能**: 订单操作、查询、数据管理

#### 5. **Gateway（网关）**
- **URL**: http://192.168.0.48:8081
- **用途**: Webhook 入口、第三方集成
- **功能**: 接收来自通路的 webhook 事件

---

## 💾 数据库和缓存（开发者工具）

### PostgreSQL
```bash
主机: 192.168.0.48
端口: 5433
数据库: simpleec
用户名: simpleec
密码: simpleec123

# 连接示例（从 192.168 网络的其他机器）:
psql -h 192.168.0.48 -p 5433 -U simpleec -d simpleec
```

### Redis
```bash
主机: 192.168.0.48
端口: 6379
密码: 无

# 连接示例:
redis-cli -h 192.168.0.48 -p 6379
```

### Kafka
```bash
Bootstrap Servers: 192.168.0.48:9092
```

---

## 📱 从 192.168 网络的其他机器访问

### Windows / macOS / Linux

**方法 1: 直接在浏览器中打开**
```
http://192.168.0.48:8088  → Kafka UI
http://192.168.0.48:3000  → Grafana
http://192.168.0.48:9090  → Prometheus
http://192.168.0.48:8082  → API
```

**方法 2: 使用命令行（Linux/macOS）**
```bash
# 测试 API
curl http://192.168.0.48:8082

# 连接到 Kafka
kafka-console-consumer --bootstrap-server 192.168.0.48:9092 --topic order.process

# 连接到数据库
psql -h 192.168.0.48 -p 5433 -U simpleec -d simpleec
```

**方法 3: 使用数据库客户端工具（DBeaver、DataGrip 等）**
```
Host: 192.168.0.48
Port: 5433
Database: simpleec
User: simpleec
Password: simpleec123
```

---

## ✅ 服务运行状态

| 服务 | 状态 | 端口 | 网址 |
|------|------|------|------|
| PostgreSQL | ✅ Running | 5433 | 192.168.0.48:5433 |
| Redis | ✅ Running | 6379 | 192.168.0.48:6379 |
| Kafka | ✅ Running | 9092 | 192.168.0.48:9092 |
| Kafka UI | ✅ Running | 8088 | http://192.168.0.48:8088 |
| API | ✅ Running | 8082 | http://192.168.0.48:8082 |
| Gateway | ✅ Running | 8081 | http://192.168.0.48:8081 |
| Grafana | ✅ Running | 3000 | http://192.168.0.48:3000 |
| Prometheus | ✅ Running | 9090 | http://192.168.0.48:9090 |

---

## 🚀 快速开始

### 1. 在浏览器中查看消息流
```
http://192.168.0.48:8088
```
- 点击左侧 "Topics" 查看所有 Kafka topics
- 点击 "order.process" 查看订单处理消息
- 实时监控消息队列

### 2. 打开监控仪表板
```
http://192.168.0.48:3000
用户: admin
密码: admin
```
- 查看订单吞吐量
- 监控系统延迟
- 检查各服务健康状态

### 3. 查询历史数据
```
psql -h 192.168.0.48 -p 5433 -U simpleec -d simpleec

# 查看最新订单
SELECT id, channel_order_id, status, created_at
FROM orders
ORDER BY created_at DESC
LIMIT 10;

# 查看商家列表
SELECT id, merchant_name FROM merchant;

# 查看通路列表
SELECT id, channel_name, platform_id FROM channel;
```

---

## 🔧 常见操作

### 查看实时日志（从本机执行）
```bash
# API 日志
docker compose logs -f simpleec-api

# 订单处理日志
docker compose logs -f simpleec-order-job

# Channel Job 日志
docker compose logs -f simpleec-channel-momo-fast
```

### 建立测试数据
```bash
./create-test-data.sh
```

### 重启服务
```bash
docker compose restart simpleec-api
docker compose restart simpleec-order-job
```

---

## 📊 系统架构概览

```
┌─────────────────────────────────────────────────────┐
│ 来自通路的 Webhook                                   │
│ (MOMO, Shopee, Yahoo, PChome, Cyberbiz)             │
└────────────────────┬────────────────────────────────┘
                     │
                     ↓
        ┌────────────────────────┐
        │  Gateway (8081)        │ ← http://192.168.0.48:8081
        │  API (8082)            │ ← http://192.168.0.48:8082
        └────────────┬───────────┘
                     │
                     ↓
        ┌──────────────────────────────┐
        │  Kafka Broker (9092)         │
        │  Topics: order.*, return.*   │
        └────────────┬─────────────────┘
                     │
        ┌────────────┴────────────┐
        ↓                         ↓
  ┌─────────────┐         ┌──────────────┐
  │ Channel     │         │ Order        │
  │ Jobs        │         │ Process Job  │
  │ (×8)        │         │              │
  └──────┬──────┘         └───────┬──────┘
         │                        │
         └────────────┬───────────┘
                      ↓
         ┌────────────────────────┐
         │  PostgreSQL (5433)     │
         │  Redis (6379)          │
         └────────────────────────┘
                      │
        ┌─────────────┴─────────────┐
        ↓                           ↓
   ┌─────────────┐          ┌──────────────┐
   │ Grafana     │          │ Prometheus   │
   │ (3000)      │          │ (9090)       │
   └─────────────┘          └──────────────┘
        ↑                           ↑
        └─────────┬─────────────────┘
                  │
          从浏览器打开：
          • http://192.168.0.48:3000
          • http://192.168.0.48:9090
          • http://192.168.0.48:8088
```

---

## 📞 故障排除

### 无法连接到服务
1. **检查网络连接**
   ```bash
   ping 192.168.0.48
   ```

2. **检查服务运行状态**
   ```bash
   docker compose ps
   ```

3. **检查防火墙**
   - 确保端口 8082, 8081, 8088, 3000, 9090, 5433, 6379 已开放

### 数据库连接问题
```bash
# 从本机测试 PostgreSQL
docker compose exec postgres psql -U simpleec -d simpleec -c "SELECT 1"

# 检查数据库日志
docker compose logs postgres
```

### Kafka 消息未流通
```bash
# 查看 Kafka 日志
docker compose logs kafka

# 列出所有 topics
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

---

## 📝 笔记

- 所有服务都配置为从任何网络接口访问（0.0.0.0）
- 数据持久化到 `./data/` 目录
- 重启 Docker 时数据不会丢失
- 默认所有权限为开放（仅用于开发环境）

**Last Updated**: 2026-02-20
**System**: SimpleEC OMS MVP Phase 1
