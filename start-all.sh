#!/bin/bash

# SimpleEC OMS - 完整系統啟動腳本
# 包含：數據庫、緩存、消息隊列、事件流 Job、後端 API、前端

set -e

echo "════════════════════════════════════════════════════════════"
echo "  SimpleEC OMS 完整系統啟動"
echo "════════════════════════════════════════════════════════════"
echo ""

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

# 顏色定義
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 1. 啟動 Docker 容器 (基礎設施 + 事件流 Job)
echo -e "${BLUE}[1/4] 啟動 Docker 容器...${NC}"
echo "     - PostgreSQL 資料庫 (port 5433)"
echo "     - Redis 緩存 (port 6379)"
echo "     - Kafka 事件流 (port 9092)"
echo "     - 事件流 Job (scheduler, backend, frontend, order 等)"
echo ""

docker compose up -d 2>&1 || docker-compose up -d 2>&1

echo -e "${GREEN}✓ Docker 容器已啟動${NC}"
sleep 5

# 2. 編譯並啟動後端 API
echo ""
echo -e "${BLUE}[2/4] 編譯並啟動後端 API...${NC}"
echo "     - 編譯 simpleec-core 和 simpleec-api"
echo "     - 啟動 Spring Boot (port 8083)"
echo ""

./gradlew :simpleec-api:bootRun > /tmp/backend-api.log 2>&1 &
API_PID=$!
echo "API PID: $API_PID"

# 等待 API 啟動
echo "等待 API 啟動..."
for i in {1..60}; do
    if lsof -i :8083 2>/dev/null | grep -q LISTEN; then
        echo -e "${GREEN}✓ 後端 API 已啟動 (port 8083)${NC}"
        break
    fi
    echo -n "."
    sleep 1
    if [ $i -eq 60 ]; then
        echo -e "${YELLOW}⚠ API 啟動超時，檢查 /tmp/backend-api.log${NC}"
    fi
done

# 3. 啟動前端 (Vite 開發伺服器)
echo ""
echo -e "${BLUE}[3/4] 啟動前端開發伺服器...${NC}"
echo "     - Vue 3 + Vite + TypeScript"
echo "     - User App (port 5173)"
echo ""

cd user-app
nohup npm run dev > /tmp/frontend.log 2>&1 &
FRONTEND_PID=$!
echo "Frontend PID: $FRONTEND_PID"

# 等待前端啟動
echo "等待前端啟動..."
for i in {1..30}; do
    if lsof -i :5173 2>/dev/null | grep -q LISTEN; then
        echo -e "${GREEN}✓ 前端已啟動 (port 5173)${NC}"
        break
    fi
    echo -n "."
    sleep 1
done

cd ..

# 4. 顯示完整系統狀態和連線資訊
echo ""
echo "════════════════════════════════════════════════════════════"
echo -e "${GREEN}  系統已完全啟動! 🚀${NC}"
echo "════════════════════════════════════════════════════════════"
echo ""
echo -e "${YELLOW}📍 連線資訊:${NC}"
echo ""
echo "  前端頁面:"
echo "    • 本機:    http://localhost:5173"
echo "    • 遠端:    http://[伺服器IP]:5173"
echo ""
echo "  後端 API:"
echo "    • 本機:    http://localhost:8083/api"
echo "    • 遠端:    http://[伺服器IP]:8083/api"
echo ""
echo "  基礎設施:"
echo "    • PostgreSQL:  localhost:5433"
echo "    • Redis:       localhost:6379"
echo "    • Kafka:       localhost:9092"
echo ""
echo -e "${YELLOW}🔥 啟用的服務:${NC}"
echo ""

# 檢查各服務狀態
services_ok=true

if docker ps | grep -q simpleec-postgres; then
    echo -e "${GREEN}  ✓${NC} 資料庫 (PostgreSQL)"
else
    echo -e "${YELLOW}  ✗${NC} 資料庫"
    services_ok=false
fi

if docker ps | grep -q simpleec-redis; then
    echo -e "${GREEN}  ✓${NC} 緩存 (Redis)"
else
    echo -e "${YELLOW}  ✗${NC} 緩存"
    services_ok=false
fi

if docker ps | grep -q simpleec-kafka; then
    echo -e "${GREEN}  ✓${NC} 訊息佇列 (Kafka)"
else
    echo -e "${YELLOW}  ✗${NC} 訊息佇列"
    services_ok=false
fi

if docker ps | grep -q simpleec-scheduler-job; then
    echo -e "${GREEN}  ✓${NC} 事件流 Job (Scheduler)"
else
    echo -e "${YELLOW}  ✗${NC} 事件流 Job"
    services_ok=false
fi

if lsof -i :8083 2>/dev/null | grep -q LISTEN; then
    echo -e "${GREEN}  ✓${NC} 後端 API (Spring Boot)"
else
    echo -e "${YELLOW}  ✗${NC} 後端 API"
    services_ok=false
fi

if lsof -i :5173 2>/dev/null | grep -q LISTEN; then
    echo -e "${GREEN}  ✓${NC} 前端頁面 (Vue 3 + Vite)"
else
    echo -e "${YELLOW}  ✗${NC} 前端頁面"
    services_ok=false
fi

echo ""
echo -e "${YELLOW}📝 快速測試:${NC}"
echo ""
echo "  1. 在瀏覽器打開前端: http://[伺服器IP]:5173"
echo "  2. 使用商家帳號登入"
echo "  3. 檢查後端 API:"
echo "     curl http://[伺服器IP]:8083/api/auth/me"
echo ""

if [ "$services_ok" = false ]; then
    echo -e "${YELLOW}⚠ 某些服務可能未完全啟動，請檢查日誌:${NC}"
    echo "  • 後端:   tail -f /tmp/backend-api.log"
    echo "  • 前端:   tail -f /tmp/frontend.log"
    echo "  • Docker: docker-compose logs -f"
fi

echo ""
echo -e "${YELLOW}🛑 停止系統:${NC}"
echo "  bash stop-all.sh"
echo ""
