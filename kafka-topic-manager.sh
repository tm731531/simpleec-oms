#!/bin/bash
# Kafka Topic Manager Tool
# Utilities for managing Kafka topics in SimpleEC OMS

set -e

KAFKA_BROKER="${KAFKA_BROKER:-localhost:9092}"
KAFKA_CONTAINER="simpleec-kafka"
RETENTION_MS=3600000    # 1 hour
PARTITIONS=3            # Platform topics
SYS_PARTITIONS=1        # System topics

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Helper functions
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[✓]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[✗]${NC} $1"
}

check_kafka() {
    if ! docker ps | grep -q $KAFKA_CONTAINER; then
        log_error "Kafka container '$KAFKA_CONTAINER' is not running"
        exit 1
    fi
    log_success "Kafka container is running"
}

# List all topics
list_topics() {
    log_info "Listing all Kafka topics..."
    docker exec $KAFKA_CONTAINER kafka-topics.sh --bootstrap-server localhost:9092 --list | sort
}

# Describe topics with details
describe_topics() {
    log_info "Describing all Kafka topics..."
    docker exec $KAFKA_CONTAINER kafka-topics.sh --bootstrap-server localhost:9092 --describe
}

# Create platform topics
create_platform_topics() {
    local partitions=${1:-$PARTITIONS}

    log_info "Creating platform topics (partitions=$partitions)..."

    for platform in cyberbiz momo pchome shopee yahoo shopline shopify; do
        for channel in fast slow; do
            local topic="${platform}.${channel}"

            # Check if topic exists
            if docker exec $KAFKA_CONTAINER kafka-topics.sh --bootstrap-server localhost:9092 --list | grep -q "^${topic}$"; then
                log_warn "Topic '$topic' already exists"
            else
                log_info "Creating topic '$topic'..."
                docker exec $KAFKA_CONTAINER kafka-topics.sh \
                    --bootstrap-server localhost:9092 \
                    --create \
                    --topic "$topic" \
                    --partitions "$partitions" \
                    --replication-factor 1 \
                    --config retention.ms=$RETENTION_MS \
                    2>&1 | grep -v "^Created topic\|already exists" || true
                log_success "Topic '$topic' created"
            fi
        done
    done
}

# Create system topics
create_system_topics() {
    log_info "Creating system topics..."

    for topic in scheduler task.backend task.failed task.frontend consumer-lag-tracking; do
        # Check if topic exists
        if docker exec $KAFKA_CONTAINER kafka-topics.sh --bootstrap-server localhost:9092 --list | grep -q "^${topic}$"; then
            log_warn "Topic '$topic' already exists"
        else
            log_info "Creating topic '$topic'..."
            docker exec $KAFKA_CONTAINER kafka-topics.sh \
                --bootstrap-server localhost:9092 \
                --create \
                --topic "$topic" \
                --partitions $SYS_PARTITIONS \
                --replication-factor 1 \
                --config retention.ms=$RETENTION_MS \
                2>&1 | grep -v "^Created topic\|already exists" || true
            log_success "Topic '$topic' created"
        fi
    done
}

# Create order topics
create_order_topics() {
    log_info "Creating order topics..."

    local topic="order.process"

    if docker exec $KAFKA_CONTAINER kafka-topics.sh --bootstrap-server localhost:9092 --list | grep -q "^${topic}$"; then
        log_warn "Topic '$topic' already exists"
    else
        log_info "Creating topic '$topic'..."
        docker exec $KAFKA_CONTAINER kafka-topics.sh \
            --bootstrap-server localhost:9092 \
            --create \
            --topic "$topic" \
            --partitions $PARTITIONS \
            --replication-factor 1 \
            --config retention.ms=$RETENTION_MS \
            2>&1 | grep -v "^Created topic\|already exists" || true
        log_success "Topic '$topic' created"
    fi
}

# Create all topics
create_all_topics() {
    log_info "========== Creating All Kafka Topics =========="
    create_platform_topics
    create_order_topics
    create_system_topics
    log_info "========== All Topics Created =========="
    echo ""
    list_topics
}

# Delete a topic
delete_topic() {
    local topic=$1

    if [ -z "$topic" ]; then
        log_error "Topic name required"
        return 1
    fi

    log_warn "Deleting topic '$topic'..."
    read -p "Are you sure? (yes/no) " -r
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        docker exec $KAFKA_CONTAINER kafka-topics.sh \
            --bootstrap-server localhost:9092 \
            --delete \
            --topic "$topic"
        log_success "Topic '$topic' deleted"
    else
        log_info "Delete cancelled"
    fi
}

# Check consumer groups
check_consumer_groups() {
    log_info "Consumer Groups:"
    docker exec $KAFKA_CONTAINER kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list 2>/dev/null || {
        log_warn "No consumer groups found (they are created lazily when services first consume messages)"
    }
}

# Monitor consumer lag
monitor_lag() {
    local group=$1

    if [ -z "$group" ]; then
        log_error "Consumer group required"
        return 1
    fi

    log_info "Consumer Group: $group"
    docker exec $KAFKA_CONTAINER kafka-consumer-groups.sh \
        --bootstrap-server localhost:9092 \
        --group "$group" \
        --describe
}

# Show usage
usage() {
    cat << EOF
Kafka Topic Manager for SimpleEC OMS

Usage: $0 <command> [options]

Commands:
  list                  List all topics
  describe              Describe all topics with details
  create-all            Create all required topics
  create-platform       Create platform topics
  create-system         Create system topics
  create-order          Create order topics
  delete <topic>        Delete a topic
  consumer-groups       List consumer groups
  monitor-lag <group>   Monitor consumer group lag
  check                 Check Kafka broker and container status
  help                  Show this help message

Examples:
  $0 list
  $0 describe
  $0 create-all
  $0 delete order.process
  $0 consumer-groups
  $0 monitor-lag order-service-group

EOF
}

# Main command handling
main() {
    local cmd=$1

    case "$cmd" in
        list)
            check_kafka
            list_topics
            ;;
        describe)
            check_kafka
            describe_topics
            ;;
        create-all)
            check_kafka
            create_all_topics
            ;;
        create-platform)
            check_kafka
            create_platform_topics
            ;;
        create-system)
            check_kafka
            create_system_topics
            ;;
        create-order)
            check_kafka
            create_order_topics
            ;;
        delete)
            check_kafka
            delete_topic "$2"
            ;;
        consumer-groups)
            check_kafka
            check_consumer_groups
            ;;
        monitor-lag)
            check_kafka
            monitor_lag "$2"
            ;;
        check)
            check_kafka
            echo ""
            log_info "Kafka broker version:"
            docker exec $KAFKA_CONTAINER kafka-broker-api-versions.sh --bootstrap-server localhost:9092 | head -1
            ;;
        help|--help|-h|"")
            usage
            ;;
        *)
            log_error "Unknown command: $cmd"
            usage
            exit 1
            ;;
    esac
}

main "$@"
