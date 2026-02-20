# SimpleEC OMS - Development Makefile
# 簡化開發命令

.PHONY: help dev-up dev-down dev-logs dev-clean dev-ps build

# 顏色定義
BLUE := \033[0;34m
GREEN := \033[0;32m
RED := \033[0;31m
YELLOW := \033[0;33m
NC := \033[0m # No Color

help:
	@echo "$(BLUE)SimpleEC OMS - Development Commands$(NC)"
	@echo ""
	@echo "$(GREEN)快速啟動 (推薦):$(NC)"
	@echo "  $(BLUE)make dev-up$(NC)          一鍵啟動開發環境 (PostgreSQL + Redis + Kafka + API + Gateway)"
	@echo "  $(BLUE)make dev-down$(NC)        關閉開發環境"
	@echo "  $(BLUE)make dev-restart$(NC)     重啟開發環境"
	@echo ""
	@echo "$(GREEN)監控與調試:$(NC)"
	@echo "  $(BLUE)make dev-logs$(NC)        查看所有容器日誌 (持續跟蹤)"
	@echo "  $(BLUE)make dev-logs-api$(NC)    查看 API 服務日誌"
	@echo "  $(BLUE)make dev-logs-gw$(NC)     查看 Gateway 日誌"
	@echo "  $(BLUE)make dev-ps$(NC)          查看容器運行狀態"
	@echo ""
	@echo "$(GREEN)清理與重置:$(NC)"
	@echo "  $(BLUE)make dev-clean$(NC)       停止容器並清空數據 (重新初始化)"
	@echo "  $(BLUE)make dev-hard-clean$(NC)  刪除所有鏡像和數據"
	@echo ""
	@echo "$(GREEN)構建與部署:$(NC)"
	@echo "  $(BLUE)make build$(NC)           構建所有服務鏡像"
	@echo "  $(BLUE)make build-api$(NC)       只構建 API 服務"
	@echo "  $(BLUE)make build-gateway$(NC)   只構建 Gateway 服務"
	@echo ""
	@echo "$(GREEN)服務訪問:$(NC)"
	@echo "  API:           $(BLUE)http://localhost:8082$(NC)"
	@echo "  Gateway:       $(BLUE)http://localhost:8081$(NC)"
	@echo "  Kafka UI:      $(BLUE)http://localhost:8088$(NC)"
	@echo "  PgAdmin:       $(BLUE)http://localhost:5050$(NC) (admin@simpleec.local / admin)"
	@echo "  PostgreSQL:    $(BLUE)localhost:5433$(NC) (simpleec / simpleec123)"
	@echo "  Redis:         $(BLUE)localhost:6379$(NC)"
	@echo "  Kafka:         $(BLUE)localhost:9092$(NC)"
	@echo ""

# ============ 快速啟動 ============

dev-up:
	@echo "$(GREEN)🚀 啟動 SimpleEC OMS 開發環境...$(NC)"
	@mkdir -p data/{postgres,redis,kafka}
	@docker-compose -f docker-compose.dev.yml up -d
	@echo "$(GREEN)✅ 開發環境已啟動!$(NC)"
	@sleep 10
	@make dev-status

dev-down:
	@echo "$(YELLOW)⏹️ 停止開發環境...$(NC)"
	@docker-compose -f docker-compose.dev.yml down
	@echo "$(GREEN)✅ 開發環境已停止$(NC)"

dev-restart:
	@echo "$(YELLOW)🔄 重啟開發環境...$(NC)"
	@docker-compose -f docker-compose.dev.yml restart
	@echo "$(GREEN)✅ 開發環境已重啟$(NC)"

# ============ 監控與調試 ============

dev-logs:
	@docker-compose -f docker-compose.dev.yml logs -f

dev-logs-api:
	@docker-compose -f docker-compose.dev.yml logs -f simpleec-api

dev-logs-gw:
	@docker-compose -f docker-compose.dev.yml logs -f simpleec-gateway

dev-ps:
	@echo "$(BLUE)容器狀態:$(NC)"
	@docker-compose -f docker-compose.dev.yml ps

dev-status:
	@echo "$(BLUE)服務檢查:$(NC)"
	@docker-compose -f docker-compose.dev.yml ps --format "table {{.Names}}\t{{.Status}}"

# ============ 清理與重置 ============

dev-clean:
	@echo "$(RED)⚠️ 清空開發數據並重新啟動...$(NC)"
	@docker-compose -f docker-compose.dev.yml down -v
	@rm -rf data/{postgres,redis,kafka}
	@mkdir -p data/{postgres,redis,kafka}
	@docker-compose -f docker-compose.dev.yml up -d
	@echo "$(GREEN)✅ 開發環境已重置$(NC)"

dev-hard-clean:
	@echo "$(RED)⚠️ 硬清理: 刪除所有鏡像、容器和數據...$(NC)"
	@docker-compose -f docker-compose.dev.yml down -v --rmi all
	@rm -rf data/*
	@echo "$(GREEN)✅ 完全清理完成$(NC)"

# ============ 構建 ============

build:
	@echo "$(BLUE)🔨 構建所有服務鏡像...$(NC)"
	@docker-compose -f docker-compose.dev.yml build --no-cache
	@echo "$(GREEN)✅ 構建完成$(NC)"

build-api:
	@echo "$(BLUE)🔨 構建 API 鏡像...$(NC)"
	@docker-compose -f docker-compose.dev.yml build --no-cache simpleec-api
	@echo "$(GREEN)✅ API 構建完成$(NC)"

build-gateway:
	@echo "$(BLUE)🔨 構建 Gateway 鏡像...$(NC)"
	@docker-compose -f docker-compose.dev.yml build --no-cache simpleec-gateway
	@echo "$(GREEN)✅ Gateway 構建完成$(NC)"

# ============ 進階 ============

dev-shell-api:
	@docker exec -it simpleec-api /bin/sh

dev-shell-db:
	@docker exec -it simpleec-postgres psql -U simpleec -d simpleec

dev-shell-redis:
	@docker exec -it simpleec-redis redis-cli

dev-test:
	@echo "$(BLUE)🧪 測試 API...$(NC)"
	@curl -s http://localhost:8082/actuator/health | jq .
	@echo ""
	@echo "$(GREEN)API 健康檢查通過✅$(NC)"

# ============ 快捷命令別名 ============

up: dev-up
down: dev-down
restart: dev-restart
logs: dev-logs
ps: dev-ps
clean: dev-clean

.DEFAULT_GOAL := help
