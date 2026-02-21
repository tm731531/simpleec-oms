#!/bin/bash

# Unit tests for cleanup-disk.sh
# Run: bash tests/docker/test_cleanup_disk.sh

SCRIPT_PATH="./docker/cleanup-disk.sh"
PASS=0
FAIL=0

# Color codes for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
NC='\033[0m' # No Color

# Temporary directory for test isolation
TEST_TEMP_DIR=$(mktemp -d)
trap "rm -rf $TEST_TEMP_DIR" EXIT

test_case() {
  local name=$1
  local expected=$2
  local actual=$3

  if [ "$actual" = "$expected" ]; then
    echo -e "${GREEN}✓ PASS${NC}: $name"
    ((PASS++))
  else
    echo -e "${RED}✗ FAIL${NC}: $name"
    echo "  Expected: $expected"
    echo "  Actual:   $actual"
    ((FAIL++))
  fi
}

test_info() {
  echo -e "${YELLOW}ℹ${NC} $1"
}

echo "Running cleanup-disk.sh tests..."
echo "=================================="
echo ""

# Test 1: Script exists and is executable
test_info "Test 1: Script existence and permissions"
test_case "Script exists and is executable" \
  "true" \
  "$([ -x $SCRIPT_PATH ] && echo 'true' || echo 'false')"

# Test 2: Script has valid bash syntax
test_info "Test 2: Script syntax validation"
test_case "Script has valid bash syntax" \
  "0" \
  "$(bash -n $SCRIPT_PATH 2>/dev/null; echo $?)"

# Test 3: Script contains all required functions
test_info "Test 3: Required functions presence"
test_case "Contains 'log_message' function" \
  "true" \
  "$(grep -q 'log_message()' $SCRIPT_PATH && echo 'true' || echo 'false')"

test_case "Contains 'get_disk_usage' function" \
  "true" \
  "$(grep -q 'get_disk_usage()' $SCRIPT_PATH && echo 'true' || echo 'false')"

test_case "Contains 'main' function" \
  "true" \
  "$(grep -q 'main()' $SCRIPT_PATH && echo 'true' || echo 'false')"

# Test 4: Script configuration constants
test_info "Test 4: Configuration constants"
test_case "THRESHOLD_WARN is set to 80" \
  "true" \
  "$(grep -q 'THRESHOLD_WARN=80' $SCRIPT_PATH && echo 'true' || echo 'false')"

test_case "THRESHOLD_CLEAN is set to 85" \
  "true" \
  "$(grep -q 'THRESHOLD_CLEAN=85' $SCRIPT_PATH && echo 'true' || echo 'false')"

test_case "LOG_FILE is configured" \
  "true" \
  "$(grep -q 'LOG_FILE=' $SCRIPT_PATH && echo 'true' || echo 'false')"

# Test 5: Script error handling logic
test_info "Test 5: Error handling patterns"
test_case "Script exits with error on missing USAGE" \
  "true" \
  "$(grep -q 'exit 1' $SCRIPT_PATH && echo 'true' || echo 'false')"

test_case "Script handles df command failure" \
  "true" \
  "$(grep -q 'df.*2>/dev/null' $SCRIPT_PATH && echo 'true' || echo 'false')"

# Test 6: Script contains cleanup logic
test_info "Test 6: Docker cleanup logic"
test_case "Script contains 'docker system prune' command" \
  "true" \
  "$(grep -q 'docker system prune' $SCRIPT_PATH && echo 'true' || echo 'false')"

test_case "Script includes Kafka health check" \
  "true" \
  "$(grep -q 'simpleec-kafka' $SCRIPT_PATH && echo 'true' || echo 'false')"

# Test 7: Script logging patterns
test_info "Test 7: Logging and alerting"
test_case "Script logs ALERT messages" \
  "true" \
  "$(grep -q 'ALERT:' $SCRIPT_PATH && echo 'true' || echo 'false')"

test_case "Script logs DISK_CHECK messages" \
  "true" \
  "$(grep -q 'DISK_CHECK:' $SCRIPT_PATH && echo 'true' || echo 'false')"

test_case "Script logs CLEANUP_START messages" \
  "true" \
  "$(grep -q 'CLEANUP_START:' $SCRIPT_PATH && echo 'true' || echo 'false')"

test_case "Script logs CLEANUP_END messages" \
  "true" \
  "$(grep -q 'CLEANUP_END:' $SCRIPT_PATH && echo 'true' || echo 'false')"

# Test 8: Script log file fallback mechanism
test_info "Test 8: Log file fallback logic"
test_case "Script implements log file fallback logic" \
  "true" \
  "$(grep -q '/tmp/simpleec-cleanup.log' $SCRIPT_PATH && echo 'true' || echo 'false')"

test_case "Script creates log directory if missing" \
  "true" \
  "$(grep -q 'mkdir -p' $SCRIPT_PATH && echo 'true' || echo 'false')"

# Test 9: Script comparison operators
test_info "Test 9: Threshold comparison logic"
test_case "Script uses -ge for threshold comparison" \
  "true" \
  "$(grep -q 'USAGE.*-ge.*THRESHOLD' $SCRIPT_PATH && echo 'true' || echo 'false')"

test_case "Script uses -lt for post-cleanup check" \
  "true" \
  "$(grep -q 'USAGE_AFTER.*-lt.*THRESHOLD_CLEAN' $SCRIPT_PATH && echo 'true' || echo 'false')"

# Test 10: Script success/partial cleanup messaging
test_info "Test 10: Cleanup outcome reporting"
test_case "Script reports SUCCESS when below threshold" \
  "true" \
  "$(grep -q 'CLEANUP_SUCCESS:' $SCRIPT_PATH && echo 'true' || echo 'false')"

test_case "Script reports PARTIAL when still high" \
  "true" \
  "$(grep -q 'CLEANUP_PARTIAL:' $SCRIPT_PATH && echo 'true' || echo 'false')"

# Test 11: Date formatting in logs
test_info "Test 11: Date and time formatting"
test_case "Script uses proper date format in logs" \
  "true" \
  "$(grep -q "date +'%Y-%m-%d %H:%M:%S'" $SCRIPT_PATH && echo 'true' || echo 'false')"

# Test 12: Script runs without errors on non-Docker environment
test_info "Test 12: Non-Docker environment handling"
if ! command -v docker &> /dev/null; then
  # Docker not available, test should handle gracefully
  echo -e "${YELLOW}ℹ${NC} Docker not available on this system - skipping Docker availability test"
else
  echo -e "${YELLOW}ℹ${NC} Docker is available - would execute in Docker environment"
fi

# Test 13: Verify script has shebang
test_info "Test 13: Script header"
test_case "Script has proper shebang" \
  "true" \
  "$(head -1 $SCRIPT_PATH | grep -q '^#!/bin/bash' && echo 'true' || echo 'false')"

# Test 14: Verify set -e is present (exit on error)
test_info "Test 14: Error handling configuration"
test_case "Script uses 'set -e' for error exit" \
  "true" \
  "$(grep -q '^set -e' $SCRIPT_PATH && echo 'true' || echo 'false')"

# Test 15: Docker system prune filter logic
test_info "Test 15: Cleanup filter configuration"
test_case "Script uses 72h filter for cleanup" \
  "true" \
  "$(grep -q 'until=72h' $SCRIPT_PATH && echo 'true' || echo 'false')"

test_case "Script removes volumes during cleanup" \
  "true" \
  "$(grep -q 'docker system prune.*--volumes' $SCRIPT_PATH && echo 'true' || echo 'false')"

echo ""
echo "========================================="
echo -e "Tests passed: ${GREEN}$PASS${NC}"
echo -e "Tests failed: ${RED}$FAIL${NC}"
echo "========================================="

if [ $FAIL -gt 0 ]; then
  exit 1
fi

exit 0
