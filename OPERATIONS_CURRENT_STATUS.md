# SimpleEC OMS - 当前运维状态 (Feb 24, 2026)

## 📊 系统状态：✅ 完全操作 (All Systems Operational)

### 核心系统
| 组件 | 状态 | 备注 |
|------|------|------|
| **PostgreSQL 数据库** | ✅ 运行 | port 5433 |
| **Redis 缓存** | ✅ 运行 | port 6379 |
| **Kafka 事件流** | ✅ 运行 | port 9092 (21 topics) |
| **Spring Boot API** | ✅ 运行 | port 8080 → 8082 (via Nginx) |
| **User Frontend** | ✅ 运行 | port 5173 → 8089 (via Nginx) |
| **Admin Frontend** | ✅ 运行 | port 8084 → 8089 (via Nginx) |
| **Nginx 反向代理** | ✅ 运行 | port 8089 |
| **Monitoring (Grafana)** | ✅ 运行 | port 3000 |

### API 端点健康状态

**公开端点**（无认证）：
- ✅ `GET /api/health` → 200
- ✅ `GET /api/version` → 200

**认证端点**（基于JWT）：
- ✅ `POST /api/auth/login` → 200 (返回JWT token)
- ✅ `GET /api/auth/me` → 200 (获取当前用户)

**管理端点**（需要认证）：
- ✅ `GET /api/admin/account` → 200
- ✅ `GET /api/admin/platform` → 200
- ✅ `GET /api/admin/merchant` → 200

**用户端点**（完整的用户事件流，需要认证）：
- ✅ `GET /api/user/channels` → 200
- ✅ `GET /api/user/products` → 200
- ✅ `GET /api/user/orders` → 200
- ✅ `GET /api/user/sellpacks` → 200
- ✅ `GET /api/user/refunds` → 200
- ✅ `GET /api/user/settings` → 200

---

## 🔧 最近的关键修复 (Feb 24, 2026)

### 修复1：API 端点路由 (Commits 0ed6853, 7f594a7)
**问题**：所有端点返回 403/404 Forbidden/Not Found
**根本原因**：Spring 控制器 @RequestMapping 缺少 `/api` 前缀
**修复**：
- HealthController: `@RequestMapping("")` → `@RequestMapping("/api")`
- AuthController: `@RequestMapping("/auth")` → `@RequestMapping("/api/auth")`
- 9个用户/后端控制器：添加 `/api` 前缀

### 修复2：系统重启版本部署 (Commit 34d5805)
**问题**：重启后 API 运行旧版本代码（有路由错误）
**根本原因**：`start-on-boot.sh` 使用 `docker compose up -d` 无 `--build` 标志
**修复**：
```bash
# 改为：
docker compose build simpleec-api simpleec-user-app simpleec-admin-app
docker compose up -d
```
**用户需要手动执行**的额外修复：
- 编辑 `/etc/systemd/system/simpleec-oms.service`
- 查看：`docker/SYSTEMD_SERVICE_FIX.md`

---

## 📖 文档导航

### 快速参考
| 文档 | 用途 |
|------|------|
| **QUICK_START.md** | 3分钟快速开始 |
| **README.md** | 项目概述和基本说明 |
| **CLAUDE.md** | 用户指示和工作流指南 |

### 开发和部署
| 文档 | 用途 |
|------|------|
| **DEPLOYMENT_GUIDE.md** | 详细的部署步骤 |
| **docker/SYSTEMD_SERVICE_FIX.md** | 系统服务修复指南 |
| **CODE_STRUCTURE.md** | 代码结构说明 |

### 架构和设计
| 文档 | 用途 |
|------|------|
| **DESIGN_v2.md** | 完整的系统设计（224KB） |
| **PLATFORM_MAPPING.md** | 7个通路的状态映射和schema |
| **SCHEMA_ALIGNMENT_ACTUAL_DB.md** | Kafka消息与数据库对齐 |
| **EVENT_SAMPLES.md** | 事件流示例 |

### 操作和监控
| 文档 | 用途 |
|------|------|
| **OPERATIONS_CURRENT_STATUS.md** | 当前状态（本文件） |
| **KAFKA_QUICKSTART.md** | Kafka操作快速参考 |
| **NETWORK_ACCESS.md** | 网络和端口配置 |

### 待整理/过时文档（可考虑清理）
```
- DESIGN.md (旧版，用DESIGN_v2.md替代)
- 多个TASKS_*_*.md (任务完成后的记录)
- FINAL_VERIFICATION_REPORT_2026-02-21.md (旧报告)
- QA_REPORT_2026-02-21.md (旧报告)
- 多个README*.md (冗余)
```

---

## 🧪 验证系统健康状态

### 1. 检查容器状态
```bash
docker compose ps
# 应该看到所有31个容器运行
```

### 2. 测试API端点
```bash
# 登录获取JWT
TOKEN=$(curl -s -X POST http://localhost:8082/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@a00000.com","password":"pass123456"}' | jq -r '.token')

# 测试用户端点
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8082/api/user/products?page=0&pageSize=10

# 应返回: { "code": 200, "data": {...} }
```

### 3. 检查启动日志
```bash
tail -f /tmp/simpleec-startup.log  # 启动过程
tail -f /tmp/simpleec-sync.log     # 同步监控
docker logs simpleec-api           # API日志
```

### 4. 监控和告警
```bash
# Grafana 仪表盘
http://localhost:3000
Username: admin / Password: admin

# Kafka UI
http://localhost:8088

# Prometheus 指标
http://localhost:9090
```

---

## 🚨 已知问题（已解决）

### ✅ Kafka Consumer Coordinator Fix (Feb 24, 01:10 AM)
**问题**：Consumer groups 无法连接 Kafka coordinator，导致无法跟踪消费offset
- 错误：`TimeoutException: Call(callName=describeGroups(api=FIND_COORDINATOR), deadlineMs=...) timed out`
- 根本原因：`__consumer_offsets` topic 不存在（内部Kafka系统topic，不是自动创建的）
- **修复**：
  1. 手动创建 `__consumer_offsets` topic（50分区，cleanup.policy=compact）
  2. 更新 `docker/init-kafka/create-topics.sh` 确保自动创建此topic
  3. 添加 `easystore.fast` topic（缺失的平台）
  4. 添加 `task.dlt` topic（缺失的系统topic）
- **验证结果**：
  - ✅ 所有6个consumer groups 现在健康运行
  - ✅ 所有 groups 的 LAG = 0（完全同步）
  - ✅ Consumer offsets 正确跟踪

**Consumer Group Status（Feb 24, 01:10）**：
| Group | Status | Topics | Lag |
|-------|--------|--------|-----|
| frontend-job-group | ✅ 运行 | task.frontend | 0 |
| channel-job-group | ✅ 运行 | cyberbiz/easystore/shopee/shopify (slow) | 0 |
| scheduler-dispatcher-group-v4 | ✅ 运行 | scheduler | 0 |
| backend-consumer-group | ✅ 运行 | task.backend | 0 |
| dlt-consumer-group | ✅ 运行 | (DLT handling) | 0 |
| retry-job-group | ✅ 运行 | (Retry handling) | 0 |

---

## 📝 操作手册

### 系统重启
```bash
# 方式1：使用启动脚本（推荐，自动rebuild）
sudo reboot
# 或手动运行
bash start-on-boot.sh

# 方式2：使用systemd（需先手动更新service）
sudo systemctl restart simpleec-oms
```

### 查看日志
```bash
# 所有容器日志
docker compose logs -f

# 特定服务
docker logs -f simpleec-api
docker logs -f simpleec-kafka
docker logs -f simpleec-postgres
```

### 更新API代码
```bash
# 1. 编辑代码
# 2. 提交并推送
git add -A && git commit -m "..."
git push origin branch-name

# 3. 重建镜像
docker compose build simpleec-api

# 4. 重启服务
docker compose up -d simpleec-api

# 5. 验证
docker logs -f simpleec-api
```

### 清理资源
```bash
# 清理Docker镜像和容器
docker compose down -v  # 包括volume（谨慎！）
docker compose down     # 不删除volume

# 清理磁盘
bash docker/cleanup-disk.sh

# 检查磁盘使用
df -h
du -sh /home/tom/ONEEC/simpleec-oms/data/*
```

---

## ✅ 最后检查清单（重启后）

- [ ] 所有容器运行中：`docker compose ps | grep -c "Up"`
- [ ] API健康检查通过：`curl http://localhost:8082/api/health`
- [ ] 用户可以登录：`http://localhost:8089`
- [ ] Admin可以访问：`http://oms-admin.tomting.com` 或 `http://localhost:8089/admin/`
- [ ] Kafka topics创建：`docker exec simpleec-kafka /opt/kafka/bin/kafka-topics.sh ...`
- [ ] Grafana仪表盘显示指标：`http://localhost:3000`

---

## 📞 常见问题排查

**Q: 重启后API报404错误？**
A: 检查是否rebuild了镜像。运行 `bash start-on-boot.sh` 或 `docker compose build simpleec-api`

**Q: 用户无法登录？**
A:
1. 检查数据库：`docker compose ps | grep postgres`
2. 检查API日志：`docker logs simpleec-api`
3. 验证密码：Test用户 `admin@a00000.com` / `pass123456`

**Q: Kafka topics 没有创建？**
A:
1. 检查Kafka状态：`docker logs simpleec-kafka | tail -20`
2. 手动创建（如果需要）：见 `KAFKA_QUICKSTART.md`

**Q: Nginx 返回502 Bad Gateway？**
A:
1. 检查后端服务是否运行：`docker compose ps | grep api`
2. 检查Nginx配置：`docker/nginx.conf`
3. 检查Nginx日志：`docker logs simpleec-nginx`

---

**最后更新**: Feb 24, 2026 12:50 AM
**维护者**: Tom
**分支**: `fix/admin-app-api-routing-and-nginx-proxy`
