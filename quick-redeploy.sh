#!/bin/bash

# SimpleEC OMS - 智能快速重啟腳本
# 功能：只重啟受影響的服務，避免重啟所有容器
# 用途：快速迭代開發 - 修改一個 JOB 後快速部署

set -e

# 配置
COMPOSE_FILE="docker-compose.yml"
DOCKER_CMD="docker compose"
COMPOSE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# ============================================================
# 功能函数
# ============================================================

# 显示用法
show_usage() {
    cat << EOF
${BLUE}SimpleEC OMS - 智能快速重啟腳本${NC}

用法：
  ${GREEN}./quick-redeploy.sh <service-name>${NC}     只重啟指定服務（更快）
  ${GREEN}./quick-redeploy.sh <svc1> <svc2> <svc3>${NC} 重啟多個服務
  ${GREEN}./quick-redeploy.sh --all${NC}              全部重啟（完整重建）
  ${GREEN}./quick-redeploy.sh --list${NC}             列出所有服務
  ${GREEN}./quick-redeploy.sh --deps <service>${NC}   顯示服務依賴
  ${GREEN}./quick-redeploy.sh --help${NC}             顯示此幫助信息

${YELLOW}常見用途：${NC}

1. 修改 simpleec-channel-job 代碼後：
   ${GREEN}./quick-redeploy.sh simpleec-channel-job${NC}

2. 修改 API 代碼後：
   ${GREEN}./quick-redeploy.sh simpleec-api${NC}

3. 連帶修改多個服務：
   ${GREEN}./quick-redeploy.sh simpleec-channel-job simpleec-order-job${NC}
   ${GREEN}./quick-redeploy.sh simpleec-api simpleec-user-app${NC}

4. 需要完整重啟所有服務：
   ${GREEN}./quick-redeploy.sh --all${NC}

5. 查看 scheduler-job 依賴哪些服務：
   ${GREEN}./quick-redeploy.sh --deps simpleec-scheduler-job${NC}

${YELLOW}服務分類：${NC}
  • Infrastructure: postgres, redis, kafka, otel-collector, tempo, loki, prometheus, grafana
  • Channel Jobs: simpleec-channel-*-{fast,slow} (7 平台 × 2 = 14 個)
  • System Jobs: simpleec-{order,scheduler,backend,frontend,retry}-job
  • API & Frontend: simpleec-{api,user-app,admin-app,nginx}

${YELLOW}開發工作流：${NC}
  1. 修改代碼
  2. 本地編譯：${GREEN}./gradlew clean build -x test${NC}
  3. 快速重啟：${GREEN}./quick-redeploy.sh <service-name>${NC}
  4. 查看日誌：${GREEN}docker logs <service-name> -f${NC}
EOF
}

# 从 docker-compose.yml 提取服务名称列表
get_all_services() {
    grep "^  [a-z]" "$COMPOSE_FILE" | grep -v "depends_on\|condition\|service" | sed 's/:$//' | xargs
}

# 列出所有服务
list_services() {
    echo -e "${BLUE}=== SimpleEC OMS 所有服務 ===${NC}\n"

    local services=($(get_all_services))

    # 基礎架構
    echo -e "${GREEN}基礎架構層：${NC}"
    for svc in postgres redis kafka kafka-init simpleec-kafka-ui; do
        if [[ " ${services[@]} " =~ " ${svc} " ]]; then
            echo "  ✓ $svc"
        fi
    done

    echo ""
    echo -e "${GREEN}監控層：${NC}"
    for svc in simpleec-prometheus simpleec-grafana simpleec-loki simpleec-otel-collector simpleec-tempo; do
        if [[ " ${services[@]} " =~ " ${svc} " ]]; then
            echo "  ✓ $svc"
        fi
    done

    echo ""
    echo -e "${GREEN}Channel Jobs (14 個 - 7 平台 × fast/slow)：${NC}"
    local channel_jobs=($(echo "${services[@]}" | tr ' ' '\n' | grep "^simpleec-channel-" | sort))
    for svc in "${channel_jobs[@]}"; do
        echo "  ✓ $svc"
    done

    echo ""
    echo -e "${GREEN}System Jobs (5 個)：${NC}"
    for svc in simpleec-order-job simpleec-scheduler-job simpleec-backend-job simpleec-frontend-job simpleec-retry-job; do
        if [[ " ${services[@]} " =~ " ${svc} " ]]; then
            echo "  ✓ $svc"
        fi
    done

    echo ""
    echo -e "${GREEN}API & Frontend：${NC}"
    for svc in simpleec-api simpleec-user-app simpleec-admin-app simpleec-nginx; do
        if [[ " ${services[@]} " =~ " ${svc} " ]]; then
            echo "  ✓ $svc"
        fi
    done

    echo ""
    echo "總計：${#services[@]} 個服務"
}

# 解析 depends_on 关系
extract_depends_on() {
    local service=$1
    local in_service=0
    local in_depends=0
    local depends=()

    while IFS= read -r line; do
        # 找到服务块开始
        if [[ $line =~ ^[[:space:]]*$service:[[:space:]]*$ ]]; then
            in_service=1
            continue
        fi

        # 如果已进入服务块，检查是否遇到新服务
        if [[ $in_service -eq 1 ]]; then
            if [[ $line =~ ^[[:space:]]*[a-z].*:[[:space:]]*$ ]] && [[ ! $line =~ ^[[:space:]]*$service ]]; then
                break
            fi

            # 寻找 depends_on
            if [[ $line =~ depends_on:[[:space:]]*$ ]]; then
                in_depends=1
                continue
            fi

            if [[ $in_depends -eq 1 ]]; then
                # 解析依赖项
                if [[ $line =~ ^[[:space:]]*- ]]; then
                    local dep=$(echo "$line" | sed 's/^[[:space:]]*-[[:space:]]*//;s/:[[:space:]]*$//' | tr -d ' ')
                    if [[ -n "$dep" ]]; then
                        depends+=("$dep")
                    fi
                elif [[ ! $line =~ ^[[:space:]]* ]]; then
                    # 如果行不以空格开头，说明 depends_on 块结束
                    in_depends=0
                fi
            fi
        fi
    done < "$COMPOSE_FILE"

    echo "${depends[@]}"
}

# 显示服务依赖
show_dependencies() {
    local service=$1

    if [[ -z "$service" ]]; then
        echo -e "${RED}錯誤：需要指定服務名稱${NC}"
        exit 1
    fi

    local all_services=($(get_all_services))

    if [[ ! " ${all_services[@]} " =~ " ${service} " ]]; then
        echo -e "${RED}錯誤：服務 '$service' 不存在${NC}"
        list_services
        exit 1
    fi

    echo -e "${BLUE}=== 服務 '$service' 的依賴 ===${NC}\n"

    local depends=($(extract_depends_on "$service"))

    if [[ ${#depends[@]} -eq 0 ]]; then
        echo -e "${YELLOW}此服務沒有依賴${NC}"
    else
        echo -e "${GREEN}直接依賴：${NC}"
        for dep in "${depends[@]}"; do
            echo "  ✓ $dep"
        done
    fi
}

# 快速重启单个或多个服务
quick_restart() {
    local services=("$@")

    if [[ ${#services[@]} -eq 0 ]]; then
        echo -e "${RED}錯誤：需要指定服務名稱${NC}"
        show_usage
        exit 1
    fi

    local all_services=($(get_all_services))

    # 验证所有指定的服务都存在
    for service in "${services[@]}"; do
        if [[ ! " ${all_services[@]} " =~ " ${service} " ]]; then
            echo -e "${RED}錯誤：服務 '$service' 不存在${NC}"
            echo -e "\n用 ${GREEN}./quick-redeploy.sh --list${NC} 查看所有可用服務"
            exit 1
        fi
    done

    # 收集所有要重启的服务（包括依赖）
    local services_to_restart=()
    local services_with_deps=()

    for service in "${services[@]}"; do
        services_with_deps+=("$service")
        services_to_restart+=("$service")

        # 获取依赖关系
        local depends=($(extract_depends_on "$service"))
        for dep in "${depends[@]}"; do
            services_with_deps+=("$dep")
            services_to_restart+=("$dep")
        done
    done

    # 去重（保持顺序）
    local unique_services=()
    local seen=""
    for svc in "${services_to_restart[@]}"; do
        if [[ ! "$seen" =~ "$svc" ]]; then
            unique_services+=("$svc")
            seen="$seen $svc"
        fi
    done

    # 显示标题
    if [[ ${#services[@]} -eq 1 ]]; then
        echo -e "${BLUE}╔════════════════════════════════════════╗${NC}"
        echo -e "${BLUE}║  快速重啟服務: ${services[0]}${NC}"
        echo -e "${BLUE}╚════════════════════════════════════════╝${NC}\n"
    else
        echo -e "${BLUE}╔════════════════════════════════════════╗${NC}"
        echo -e "${BLUE}║  快速重啟多個服務（${#services[@]} 個）${NC}"
        echo -e "${BLUE}╚════════════════════════════════════════╝${NC}\n"

        echo -e "${YELLOW}指定的服務：${NC}"
        for svc in "${services[@]}"; do
            echo "  • $svc"
        done
        echo ""
    fi

    # 显示将要重启的服务
    echo -e "${YELLOW}將要重啟的服務（共 ${#unique_services[@]} 個）：${NC}"
    for svc in "${unique_services[@]}"; do
        echo "  ► $svc"
    done
    echo ""

    # 重建镜像
    echo -e "${BLUE}[1/3] 正在重建 Docker 映像...${NC}"
    local build_failed=0
    for service in "${services[@]}"; do
        if ! $DOCKER_CMD build --no-cache "$service" 2>&1 | tail -3; then
            echo -e "${RED}✗ 映像重建失敗: $service${NC}"
            build_failed=1
        fi
    done

    if [[ $build_failed -eq 1 ]]; then
        echo -e "${RED}✗ 部分映像重建失敗${NC}"
        exit 1
    fi
    echo -e "${GREEN}✓ 映像重建完成${NC}\n"

    # 停止旧服务
    echo -e "${BLUE}[2/3] 正在停止舊服務...${NC}"
    $DOCKER_CMD stop "${unique_services[@]}" 2>/dev/null || true
    $DOCKER_CMD rm "${unique_services[@]}" 2>/dev/null || true
    echo -e "${GREEN}✓ 舊服務已停止${NC}\n"

    # 启动新服务
    echo -e "${BLUE}[3/3] 正在啟動新服務...${NC}"
    if $DOCKER_CMD up -d "${unique_services[@]}"; then
        echo -e "${GREEN}✓ 新服務已啟動${NC}\n"

        # 显示部署摘要
        echo -e "${BLUE}═══════════════════════════════════════${NC}"
        echo -e "${GREEN}✓ 部署完成！${NC}\n"

        if [[ ${#services[@]} -eq 1 ]]; then
            echo -e "${YELLOW}查看日誌：${NC}"
            echo -e "  ${GREEN}docker logs ${services[0]} -f${NC}\n"
        else
            echo -e "${YELLOW}查看日誌：${NC}"
            for svc in "${services[@]}"; do
                echo -e "  ${GREEN}docker logs $svc -f${NC}"
            done
            echo ""
        fi

        echo -e "${YELLOW}查看運行狀態：${NC}"
        echo -e "  ${GREEN}docker ps | grep simpleec${NC}\n"
        echo -e "${BLUE}═══════════════════════════════════════${NC}"
    else
        echo -e "${RED}✗ 啟動失敗${NC}"
        exit 1
    fi
}

# 全量重启
restart_all() {
    echo -e "${BLUE}╔════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║  全部重啟 (所有 Docker 容器)${NC}"
    echo -e "${BLUE}╚════════════════════════════════════════╝${NC}\n"

    echo -e "${YELLOW}警告：此操作將重啟所有 Docker 容器（耗時較長）${NC}\n"
    echo -e "${YELLOW}建議用法：${NC}"
    echo -e "  • 正常開發修改 → 用 ${GREEN}./quick-redeploy.sh <service-name>${NC}"
    echo -e "  • 修改基礎設施   → 用 ${GREEN}./quick-redeploy.sh --all${NC}"
    echo ""

    read -p "確認要全部重啟？ (y/N): " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo -e "${YELLOW}已取消${NC}"
        exit 0
    fi

    echo -e "${BLUE}[1/2] 正在完整重建...${NC}"
    cd "$COMPOSE_DIR"
    if $DOCKER_CMD down && $DOCKER_CMD up -d --build; then
        echo -e "${GREEN}✓ 所有服務已啟動${NC}\n"

        echo -e "${BLUE}═══════════════════════════════════════${NC}"
        echo -e "${GREEN}✓ 全部重啟完成！${NC}\n"
        echo -e "${YELLOW}系統初始化需要 2-3 分鐘${NC}"
        echo -e "${YELLOW}查看整體狀態：${NC}"
        echo -e "  ${GREEN}docker ps${NC}\n"
        echo -e "${YELLOW}查看 Kafka 初始化：${NC}"
        echo -e "  ${GREEN}docker logs simpleec-kafka-init -f${NC}\n"
        echo -e "${BLUE}═══════════════════════════════════════${NC}"
    else
        echo -e "${RED}✗ 重啟失敗${NC}"
        exit 1
    fi
}

# ============================================================
# 主程序
# ============================================================

# 检查前置条件
if [[ ! -f "$COMPOSE_FILE" ]]; then
    echo -e "${RED}錯誤：找不到 $COMPOSE_FILE${NC}"
    exit 1
fi

# 处理命令行参数
case "${1:-}" in
    --help|-h)
        show_usage
        ;;
    --list|-l)
        list_services
        ;;
    --deps|-d)
        show_dependencies "$2"
        ;;
    --all)
        restart_all
        ;;
    *)
        if [[ -z "$1" ]]; then
            show_usage
            exit 0
        else
            # 支持多个服务参数
            quick_restart "$@"
        fi
        ;;
esac
