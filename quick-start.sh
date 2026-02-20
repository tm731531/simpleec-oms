#!/bin/bash

# SimpleEC OMS - Quick Start Script
# 一鍵啟動開發環境

set -e

# 顏色定義
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 函數
print_header() {
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}========================================${NC}"
}

print_success() {
    echo -e "${GREEN}✅ $1${NC}"
}

print_info() {
    echo -e "${BLUE}ℹ️  $1${NC}"
}

print_error() {
    echo -e "${RED}❌ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠️  $1${NC}"
}

# 檢查 Docker
check_docker() {
    print_header "檢查 Docker"

    if ! command -v docker &> /dev/null; then
        print_error "Docker 未安裝。請先安裝 Docker: https://www.docker.com/get-started"
        exit 1
    fi
    print_success "Docker 已安裝"

    if ! command -v docker-compose &> /dev/null; then
        print_error "docker-compose 未安裝。請先安裝 Docker Compose"
        exit 1
    fi
    print_success "docker-compose 已安裝"

    # 檢查 Docker daemon
    if ! docker ps > /dev/null 2>&1; then
        print_error "Docker daemon 未運行。請啟動 Docker"
        exit 1
    fi
    print_success "Docker daemon 運行中"
}

# 創建數據目錄
setup_directories() {
    print_header "設置目錄"

    mkdir -p data/{postgres,redis,kafka}
    print_success "數據目錄已準備"
}

# 啟動容器
start_containers() {
    print_header "啟動容器"

    print_info "構建服務鏡像..."
    docker-compose -f docker-compose.dev.yml build --quiet 2>/dev/null || \
    docker-compose -f docker-compose.dev.yml build

    print_info "啟動容器 (這可能需要 1-2 分鐘)..."
    docker-compose -f docker-compose.dev.yml up -d
    print_success "容器已啟動"
}

# 等待服務就緒
wait_for_services() {
    print_header "等待服務就緒"

    local retries=30
    local count=0

    # 等待 PostgreSQL
    print_info "等待 PostgreSQL..."
    while [ $count -lt $retries ]; do
        if docker-compose -f docker-compose.dev.yml exec -T postgres pg_isready -U simpleec > /dev/null 2>&1; then
            print_success "PostgreSQL 已就緒"
            break
        fi
        count=$((count + 1))
        sleep 2
    done

    if [ $count -eq $retries ]; then
        print_warning "PostgreSQL 啟動超時，但繼續進行..."
    fi

    # 等待 Redis
    count=0
    print_info "等待 Redis..."
    while [ $count -lt $retries ]; do
        if docker-compose -f docker-compose.dev.yml exec -T redis redis-cli ping > /dev/null 2>&1; then
            print_success "Redis 已就緒"
            break
        fi
        count=$((count + 1))
        sleep 2
    done

    # 等待 Kafka
    count=0
    print_info "等待 Kafka..."
    while [ $count -lt $retries ]; do
        if docker-compose -f docker-compose.dev.yml exec -T kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 > /dev/null 2>&1; then
            print_success "Kafka 已就緒"
            break
        fi
        count=$((count + 1))
        sleep 2
    done

    # 等待 API
    count=0
    print_info "等待 API 服務..."
    while [ $count -lt $retries ]; do
        if curl -s http://localhost:8082/actuator/health > /dev/null 2>&1; then
            print_success "API 服務已就緒"
            break
        fi
        count=$((count + 1))
        sleep 2
    done

    print_success "所有服務已就緒！"
}

# 顯示訪問信息
show_access_info() {
    print_header "訪問信息"

    echo ""
    echo -e "${GREEN}🎉 SimpleEC OMS 開發環境已啟動！${NC}"
    echo ""
    echo -e "${BLUE}API 和工具:${NC}"
    echo -e "  ${BLUE}API:           ${GREEN}http://localhost:8082${NC}"
    echo -e "  ${BLUE}Gateway:       ${GREEN}http://localhost:8081${NC}"
    echo -e "  ${BLUE}Kafka UI:      ${GREEN}http://localhost:8088${NC}"
    echo -e "  ${BLUE}PgAdmin:       ${GREEN}http://localhost:5050${NC} (admin@simpleec.local / admin)"
    echo ""
    echo -e "${BLUE}數據庫連接:${NC}"
    echo -e "  ${BLUE}PostgreSQL:    ${GREEN}localhost:5433${NC} (simpleec / simpleec123)"
    echo -e "  ${BLUE}Redis:         ${GREEN}localhost:6379${NC}"
    echo -e "  ${BLUE}Kafka:         ${GREEN}localhost:9092${NC}"
    echo ""
    echo -e "${BLUE}常用命令:${NC}"
    echo -e "  ${BLUE}查看日誌:      ${GREEN}docker-compose -f docker-compose.dev.yml logs -f${NC}"
    echo -e "  ${BLUE}查看狀態:      ${GREEN}docker-compose -f docker-compose.dev.yml ps${NC}"
    echo -e "  ${BLUE}停止環境:      ${GREEN}docker-compose -f docker-compose.dev.yml down${NC}"
    echo -e "  ${BLUE}重置數據:      ${GREEN}docker-compose -f docker-compose.dev.yml down -v${NC}"
    echo ""
    echo -e "${BLUE}或使用 Makefile:${NC}"
    echo -e "  ${BLUE}make help      ${GREEN}查看所有命令${NC}"
    echo -e "  ${BLUE}make down      ${GREEN}停止環境${NC}"
    echo -e "  ${BLUE}make logs      ${GREEN}查看日誌${NC}"
    echo ""
}

# 測試 API
test_api() {
    print_info "測試 API 連接..."

    local response=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8082/actuator/health 2>/dev/null || echo "000")

    if [ "$response" = "200" ]; then
        print_success "API 連接成功"
    else
        print_warning "API 返回狀態碼: $response (可能還在啟動)"
    fi
}

# 主程序
main() {
    clear

    case "${1:-start}" in
        start)
            check_docker
            setup_directories
            start_containers
            wait_for_services
            test_api
            show_access_info
            ;;
        stop)
            print_header "停止開發環境"
            docker-compose -f docker-compose.dev.yml down
            print_success "開發環境已停止"
            ;;
        clean)
            print_header "清空開發數據"
            docker-compose -f docker-compose.dev.yml down -v
            rm -rf data/*
            print_success "開發數據已清空"
            ;;
        logs)
            docker-compose -f docker-compose.dev.yml logs -f "${2:---all}"
            ;;
        ps)
            docker-compose -f docker-compose.dev.yml ps
            ;;
        restart)
            print_header "重啟開發環境"
            docker-compose -f docker-compose.dev.yml restart
            print_success "開發環境已重啟"
            wait_for_services
            show_access_info
            ;;
        *)
            echo "使用方法: $0 {start|stop|clean|logs|ps|restart}"
            echo ""
            echo "  start   - 啟動開發環境 (默認)"
            echo "  stop    - 停止開發環境"
            echo "  clean   - 清空所有數據並重新啟動"
            echo "  logs    - 查看日誌"
            echo "  ps      - 查看容器狀態"
            echo "  restart - 重啟環境"
            exit 1
            ;;
    esac
}

main "$@"
