# SimpleEC OMS 快速命令参考

**快速复制粘贴** - 常用操作命令

---

## 🚀 系统启动和停止

```bash
# 启动所有服务（推荐，自动rebuild）
bash start-on-boot.sh

# 或使用Docker Compose
docker compose up -d

# 停止所有服务
docker compose down

# 停止并删除数据（谨慎！）
docker compose down -v
```

---

## 🔍 系统检查

```bash
# 查看所有容器状态
docker compose ps

# 查看容器数量（应该是31个）
docker compose ps | wc -l

# 查看特定服务日志
docker logs -f simpleec-api
docker logs -f simpleec-kafka
docker logs -f simpleec-postgres

# 实时查看所有日志
docker compose logs -f

# 检查启动日志
tail -f /tmp/simpleec-startup.log
```

---

## 🧪 API 测试

```bash
# 1. 登录获取JWT Token
TOKEN=$(curl -s -X POST http://localhost:8082/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@a00000.com","password":"pass123456"}' | jq -r '.token')

echo "Token: $TOKEN"

# 2. 测试健康检查（无需auth）
curl http://localhost:8082/api/health | jq .

# 3. 测试管理员端点
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8082/api/admin/account | jq .

# 4. 测试用户端点
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8082/api/user/products?page=0&pageSize=10 | jq .

# 5. 测试所有用户端点
for endpoint in products channels orders sellpacks refunds settings; do
  echo "Testing /api/user/$endpoint..."
  curl -s -H "Authorization: Bearer $TOKEN" \
    http://localhost:8082/api/user/$endpoint | jq -r '.code'
done
```

---

## 🐳 Docker 操作

```bash
# 构建特定服务镜像
docker compose build simpleec-api
docker compose build simpleec-user-app
docker compose build simpleec-admin-app

# 构建所有镜像
docker compose build

# 进入容器命令行
docker exec -it simpleec-api bash
docker exec -it simpleec-postgres psql -U simpleec

# 查看镜像大小
docker images | grep simpleec

# 删除未使用的镜像
docker image prune -f
```

---

## 📊 Kafka 操作

```bash
# 查看所有topics
docker exec simpleec-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --list

# 查看特定topic的消息
docker exec simpleec-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic PLATFORM_EVENTS \
  --from-beginning

# 查看消费者组
docker exec simpleec-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --list

# 查看消费者组详情
docker exec simpleec-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group simpleec-api \
  --describe
```

---

## 📈 监控和仪表盘

```bash
# Grafana (监控仪表盘)
http://localhost:3000
Username: admin
Password: admin

# Kafka UI (Kafka管理)
http://localhost:8088

# Prometheus (指标)
http://localhost:9090

# 应用UI
# User App:  http://localhost:5173 或 http://localhost:8089
# Admin App: http://localhost:8084 或 http://localhost:8089/admin/
# Test Account: admin@a00000.com / pass123456
```

---

## 🔧 数据库操作

```bash
# 连接PostgreSQL
docker exec -it simpleec-postgres psql -U simpleec -d simpleec

# 常用SQL
SELECT version();                  -- 查看版本
\dt                               -- 列出所有表
\d orders                         -- 查看orders表结构
SELECT COUNT(*) FROM orders;      -- 查看订单数量
SELECT * FROM orders LIMIT 5;     -- 查看前5条记录
```

---

## 📝 代码更新和部署

```bash
# 1. 修改代码后
git add -A
git commit -m "Fix: description"
git push origin branch-name

# 2. 重建API镜像
docker compose build simpleec-api

# 3. 重启API服务
docker compose up -d simpleec-api

# 4. 验证更新
docker logs -f simpleec-api
curl http://localhost:8082/api/health
```

---

## 🧹 清理和维护

```bash
# 清理磁盘（清理日志、临时文件）
bash docker/cleanup-disk.sh

# 查看磁盘使用
df -h
du -sh /home/tom/ONEEC/simpleec-oms/data/*

# 查看Docker磁盘使用
docker system df

# 清理Docker（谨慎！）
docker system prune -f          # 删除unused的镜像、容器、网络
docker volume prune -f          # 删除unused的volumes
```

---

## 🐛 故障排查

```bash
# 查看API错误日志
docker logs simpleec-api 2>&1 | grep -i "error\|exception" | tail -20

# 查看Kafka错误
docker logs simpleec-kafka 2>&1 | grep -i "error" | tail -20

# 查看Nginx错误
docker logs simpleec-nginx 2>&1 | tail -20

# 检查端口占用
lsof -i :8080
lsof -i :5173
lsof -i :8084

# 检查网络连接
docker network ls
docker network inspect simpleec-oms_default
```

---

## 🔄 系统重启验证

```bash
# 重启系统
sudo reboot

# 等待启动完成（约1-2分钟）
sleep 120

# 验证所有容器运行
docker compose ps

# 验证API健康
curl http://localhost:8082/api/health

# 验证用户可以登录
curl -X POST http://localhost:8082/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@a00000.com","password":"pass123456"}' | jq .
```

---

## ⚡ 一行命令大全

```bash
# 完整系统健康检查
docker compose ps && echo "=== API ===" && curl -s http://localhost:8082/api/health | jq .code

# 快速重启API
docker compose build simpleec-api && docker compose up -d simpleec-api && docker logs -f simpleec-api

# 查看系统资源使用
docker stats --no-stream

# 导出所有日志
docker compose logs > /tmp/all-logs.txt && echo "Logs exported to /tmp/all-logs.txt"

# 快速清理和重启
docker compose down && docker volume prune -f && docker compose up -d
```

---

## 📞 故障快速参考

| 问题 | 命令 |
|------|------|
| API返回404 | `docker logs simpleec-api \| grep "No mapping"` |
| 无法连接数据库 | `docker logs simpleec-postgres` |
| Kafka topics不存在 | `docker logs simpleec-kafka \| grep "created"` |
| Nginx 502错误 | `docker logs simpleec-nginx` |
| 端口被占用 | `lsof -i :PORT` |
| 磁盘满 | `bash docker/cleanup-disk.sh` |

---

**提示**: 将常用命令保存到 shell alias 或 `.bashrc`：
```bash
alias soms-ps='docker compose ps'
alias soms-logs='docker compose logs -f'
alias soms-api-logs='docker logs -f simpleec-api'
alias soms-restart='docker compose up -d simpleec-api && docker logs -f simpleec-api'
```

---

**最后更新**: Feb 24, 2026
