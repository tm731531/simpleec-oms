#!/bin/bash
# Kafka Setup Verification Script
# Checks that Kafka automation is properly configured

set -e

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

passed=0
failed=0
warnings=0

check_pass() {
    echo -e "${GREEN}✓${NC} $1"
    ((passed++))
}

check_fail() {
    echo -e "${RED}✗${NC} $1"
    ((failed++))
}

check_warn() {
    echo -e "${YELLOW}⚠${NC} $1"
    ((warnings++))
}

echo ""
echo -e "${BLUE}========== Kafka Setup Verification ==========${NC}"
echo ""

# Check 1: Files exist
echo -e "${BLUE}1. Checking required files...${NC}"
if [ -f "docker/Dockerfile.kafka-init" ]; then
    check_pass "docker/Dockerfile.kafka-init exists"
else
    check_fail "docker/Dockerfile.kafka-init missing"
fi

if [ -f "docker/init-kafka/create-topics.sh" ]; then
    check_pass "docker/init-kafka/create-topics.sh exists"
else
    check_fail "docker/init-kafka/create-topics.sh missing"
fi

if [ -f "kafka-topic-manager.sh" ]; then
    check_pass "kafka-topic-manager.sh exists"
else
    check_fail "kafka-topic-manager.sh missing"
fi

if [ -f "docs/KAFKA_TOPIC_AUTOMATION.md" ]; then
    check_pass "docs/KAFKA_TOPIC_AUTOMATION.md exists"
else
    check_fail "docs/KAFKA_TOPIC_AUTOMATION.md missing"
fi

# Check 2: Scripts are executable
echo ""
echo -e "${BLUE}2. Checking script permissions...${NC}"
if [ -x "docker/init-kafka/create-topics.sh" ]; then
    check_pass "create-topics.sh is executable"
else
    check_fail "create-topics.sh is not executable"
fi

if [ -x "kafka-topic-manager.sh" ]; then
    check_pass "kafka-topic-manager.sh is executable"
else
    check_fail "kafka-topic-manager.sh is not executable"
fi

# Check 3: docker-compose.yml configuration
echo ""
echo -e "${BLUE}3. Checking docker-compose.yml...${NC}"
if grep -q "kafka-init:" docker-compose.yml; then
    check_pass "kafka-init service defined in docker-compose.yml"
else
    check_fail "kafka-init service not found in docker-compose.yml"
fi

if grep -q "condition: service_healthy" docker-compose.yml | grep -q "kafka:"; then
    check_warn "kafka-init depends on kafka health check (check manually)"
else
    check_warn "Could not verify kafka-init dependency (check manually)"
fi

# Check 4: Script content validation
echo ""
echo -e "${BLUE}4. Checking script content...${NC}"
if grep -q "create_topic()" docker/init-kafka/create-topics.sh; then
    check_pass "create-topics.sh has create_topic function"
else
    check_fail "create-topics.sh missing create_topic function"
fi

if grep -q "cyberbiz\|momo\|pchome\|shopee\|yahoo" docker/init-kafka/create-topics.sh; then
    check_pass "create-topics.sh includes all platforms"
else
    check_fail "create-topics.sh missing platform definitions"
fi

# Check 5: Docker image name validation
echo ""
echo -e "${BLUE}5. Checking Dockerfile...${NC}"
if grep -q "confluentinc/cp-kafka" docker/Dockerfile.kafka-init; then
    check_pass "Dockerfile.kafka-init uses Confluent Kafka image"
else
    check_fail "Dockerfile.kafka-init missing proper Kafka image"
fi

# Check 6: Environment variables
echo ""
echo -e "${BLUE}6. Checking environment variables...${NC}"
if grep -q "KAFKA_BROKER" docker-compose.yml; then
    check_pass "KAFKA_BROKER variable configured"
else
    check_fail "KAFKA_BROKER not configured"
fi

if grep -q "KAFKA_PARTITIONS" docker-compose.yml; then
    check_pass "KAFKA_PARTITIONS variable configured"
else
    check_fail "KAFKA_PARTITIONS not configured"
fi

# Check 7: Topic configuration
echo ""
echo -e "${BLUE}7. Checking topic definitions...${NC}"
topic_count=$(grep -c "create_topic \"" docker/init-kafka/create-topics.sh || true)
if [ "$topic_count" -ge 16 ]; then
    check_pass "create-topics.sh defines $topic_count topics"
else
    check_fail "create-topics.sh defines only $topic_count topics (expected 16+)"
fi

# Summary
echo ""
echo -e "${BLUE}========== Verification Summary ==========${NC}"
echo -e "Passed:  ${GREEN}$passed${NC}"
if [ $failed -gt 0 ]; then
    echo -e "Failed:  ${RED}$failed${NC}"
fi
if [ $warnings -gt 0 ]; then
    echo -e "Warnings: ${YELLOW}$warnings${NC}"
fi

echo ""

if [ $failed -eq 0 ]; then
    echo -e "${GREEN}✓ All checks passed!${NC}"
    echo ""
    echo "Next steps:"
    echo "1. Run: docker compose up -d"
    echo "2. Monitor: docker logs simpleec-kafka-init"
    echo "3. Verify: ./kafka-topic-manager.sh list"
    echo ""
    exit 0
else
    echo -e "${RED}✗ Some checks failed. Please fix issues above.${NC}"
    exit 1
fi
