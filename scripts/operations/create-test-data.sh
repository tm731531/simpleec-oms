#!/bin/bash
# SimpleEC OMS - Create Test Data Script
# Creates test merchant, channels, products, and sample orders

set -e

echo "=========================================="
echo "SimpleEC OMS - Test Data Creation"
echo "=========================================="

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Verify PostgreSQL is running
echo -e "${BLUE}1. Checking PostgreSQL connectivity...${NC}"
docker compose exec -T postgres pg_isready -U simpleec &>/dev/null || {
    echo "ERROR: PostgreSQL not running or not accessible"
    exit 1
}
echo -e "${GREEN}✓ PostgreSQL is ready${NC}"

# Check if test data already exists
echo -e "${BLUE}2. Checking for existing test data...${NC}"
MERCHANT_COUNT=$(docker compose exec -T postgres psql -U simpleec -d simpleec -tAc "SELECT COUNT(*) FROM merchant;")
if [ "$MERCHANT_COUNT" -gt 0 ]; then
    echo -e "${GREEN}✓ Test data already exists ($MERCHANT_COUNT merchant(s))${NC}"
    echo ""
    echo "Current test data:"
    docker compose exec -T postgres psql -U simpleec -d simpleec -c "SELECT id, merchant_name FROM merchant LIMIT 5;"
    exit 0
fi

echo -e "${BLUE}3. Creating test data from seed scripts...${NC}"
docker compose exec -T postgres psql -U simpleec -d simpleec -f /docker-entrypoint-initdb.d/02-seed-data.sql
echo -e "${GREEN}✓ Test data created${NC}"

echo ""
echo "=========================================="
echo "Test Data Summary"
echo "=========================================="

# Count records
MERCHANTS=$(docker compose exec -T postgres psql -U simpleec -d simpleec -tAc "SELECT COUNT(*) FROM merchant;")
CHANNELS=$(docker compose exec -T postgres psql -U simpleec -d simpleec -tAc "SELECT COUNT(*) FROM channel;")
PRODUCTS=$(docker compose exec -T postgres psql -U simpleec -d simpleec -tAc "SELECT COUNT(*) FROM product;")
ORDERS=$(docker compose exec -T postgres psql -U simpleec -d simpleec -tAc "SELECT COUNT(*) FROM orders;")

echo "Merchants: $MERCHANTS"
echo "Channels:  $CHANNELS"
echo "Products:  $PRODUCTS"
echo "Orders:    $ORDERS"

echo ""
echo -e "${GREEN}✓ All test data created successfully!${NC}"
echo ""
echo "Available test data:"
docker compose exec -T postgres psql -U simpleec -d simpleec << 'SQL'
SELECT
    m.id as merchant_id,
    m.merchant_name,
    COUNT(DISTINCT c.id) as channels,
    COUNT(DISTINCT p.id) as products
FROM merchant m
LEFT JOIN channel c ON m.id = c.merchant_id
LEFT JOIN product p ON m.id = p.merchant_id
GROUP BY m.id, m.merchant_name;
SQL

echo ""
echo "=========================================="
echo "Next steps:"
echo "  1. View Kafka UI: http://localhost:8088"
echo "  2. Send test webhook: ./send-test-webhook.sh"
echo "  3. Monitor order processing: docker compose logs -f simpleec-order-job"
echo "=========================================="
