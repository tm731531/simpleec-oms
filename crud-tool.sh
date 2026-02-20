#!/bin/bash

# SimpleEC OMS CRUD 工具 - a00000 商家
# 使用 psql 直接操作資料庫，無需額外依賴

MERCHANT_ID="a00000"
DB_HOST="localhost"
DB_PORT="5433"
DB_NAME="simpleec"
DB_USER="simpleec"
DB_PASSWORD="simpleec123"

export PGPASSWORD=$DB_PASSWORD

# 顏色
GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 簡單的表格列印
print_table() {
    local title=$1
    local query=$2

    if [ -n "$title" ]; then
        echo -e "${BLUE}${title}${NC}"
    fi

    psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME \
        -c "$query" 2>/dev/null | tail -n +3
}

print_help() {
    cat << 'EOF'

SimpleEC OMS CRUD 工具 - a00000 商家
用法: bash crud-tool.sh <命令> [參數...]

📋 商家命令:
  merchant-info                              - 查看商家資訊
  merchant-update <欄位> <值> [欄位 值]...  - 更新商家資訊

👤 帳號命令:
  account-list                               - 列出所有帳號
  account-add <帳號ID> <名稱> <郵箱> <密碼> - 新增帳號

📦 產品命令:
  product-list [限制數]                      - 列出產品
  product-add <ID> <SKU> <名稱> <成本> <價> - 新增產品
  product-update <ID> <數量>                 - 更新庫存

📋 訂單命令:
  order-list [狀態]                          - 列出訂單
  order-detail <訂單ID>                      - 查看訂單詳情
  order-status <訂單ID> <狀態>              - 更新訂單狀態

📊 其他:
  stats                                      - 查看統計數據

例子:
  bash crud-tool.sh merchant-info
  bash crud-tool.sh account-list
  bash crud-tool.sh product-list 5
  bash crud-tool.sh order-list pending
  bash crud-tool.sh stats

EOF
}

case $1 in
    merchant-info)
        echo -e "${GREEN}商家資訊 (ID: $MERCHANT_ID)${NC}"
        psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME << EOSQL
        SELECT id, merchant_name, merchant_email, merchant_phone_number,
               tax_id_number, address_city, address_region, status, created_at
        FROM merchant WHERE id = '$MERCHANT_ID';
EOSQL
        ;;

    merchant-update)
        if [ $# -lt 3 ]; then
            echo "用法: bash crud-tool.sh merchant-update <欄位> <值> [欄位 值]..."
            exit 1
        fi

        SET_CLAUSE=""
        shift
        while [ $# -ge 2 ]; do
            if [ -z "$SET_CLAUSE" ]; then
                SET_CLAUSE="$1 = '$2'"
            else
                SET_CLAUSE="$SET_CLAUSE, $1 = '$2'"
            fi
            shift 2
        done

        psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME << EOSQL
        UPDATE merchant SET $SET_CLAUSE, updated_at = now() WHERE id = '$MERCHANT_ID';
        SELECT '✓ 已更新商家資訊' as result;
EOSQL
        ;;

    account-list)
        echo -e "${GREEN}帳號列表${NC}"
        psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME << EOSQL
        SELECT id, account_name, account_email, access_level, status, created_at::date
        FROM account WHERE merchant_id = '$MERCHANT_ID' ORDER BY created_at DESC;
EOSQL
        ;;

    account-add)
        if [ $# -lt 5 ]; then
            echo "用法: bash crud-tool.sh account-add <帳號ID> <名稱> <郵箱> <密碼>"
            exit 1
        fi

        ACCT_ID=$2
        ACCT_NAME=$3
        ACCT_EMAIL=$4
        ACCT_PASSWORD=$5

        psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME << EOSQL
        INSERT INTO account (id, account_name, account_email, account_password,
                            access_level, merchant_id, status, is_main_account)
        VALUES ('$ACCT_ID', '$ACCT_NAME', '$ACCT_EMAIL', '$ACCT_PASSWORD', 0, '$MERCHANT_ID', 'enable', false)
        ON CONFLICT (id) DO NOTHING;
        SELECT '✓ 已新增帳號: $ACCT_ID' as result;
EOSQL
        ;;

    product-list)
        LIMIT=${2:-10}
        echo -e "${GREEN}產品列表 (前 $LIMIT 筆)${NC}"
        psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME << EOSQL
        SELECT id, sku, name, cost_price, suggest_price, quantity,
               status, created_at::date FROM product
        WHERE merchant_id = '$MERCHANT_ID' ORDER BY created_at DESC LIMIT $LIMIT;
EOSQL
        ;;

    product-add)
        if [ $# -lt 6 ]; then
            echo "用法: bash crud-tool.sh product-add <ID> <SKU> <名稱> <成本> <建議價> [數量]"
            exit 1
        fi

        PROD_ID=$2
        PROD_SKU=$3
        PROD_NAME=$4
        PROD_COST=$5
        PROD_PRICE=$6
        PROD_QTY=${7:-0}

        psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME << EOSQL
        INSERT INTO product (id, merchant_id, sku, name, cost_price, suggest_price, quantity, status)
        VALUES ('$PROD_ID', '$MERCHANT_ID', '$PROD_SKU', '$PROD_NAME', $PROD_COST, $PROD_PRICE, $PROD_QTY, 'active')
        ON CONFLICT (id) DO NOTHING;
        SELECT '✓ 已新增產品: $PROD_SKU' as result;
EOSQL
        ;;

    product-update)
        if [ $# -lt 3 ]; then
            echo "用法: bash crud-tool.sh product-update <產品ID> <新數量>"
            exit 1
        fi

        PROD_ID=$2
        NEW_QTY=$3

        psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME << EOSQL
        UPDATE product SET quantity = $NEW_QTY, updated_at = now()
        WHERE id = '$PROD_ID' AND merchant_id = '$MERCHANT_ID';
        SELECT '✓ 已更新產品庫存' as result;
EOSQL
        ;;

    order-list)
        STATUS=$2
        echo -e "${GREEN}訂單列表 ${STATUS:+(狀態: $STATUS)}${NC}"
        if [ -n "$STATUS" ]; then
            psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME << EOSQL
            SELECT id, channel_id, channel_order_id, order_status, buyer_name,
                   total_amount, created_at::date FROM orders
            WHERE merchant_id = '$MERCHANT_ID' AND order_status = '$STATUS'
            ORDER BY created_at DESC LIMIT 20;
EOSQL
        else
            psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME << EOSQL
            SELECT id, channel_id, channel_order_id, order_status, buyer_name,
                   total_amount, created_at::date FROM orders
            WHERE merchant_id = '$MERCHANT_ID' ORDER BY created_at DESC LIMIT 20;
EOSQL
        fi
        ;;

    order-detail)
        if [ $# -lt 2 ]; then
            echo "用法: bash crud-tool.sh order-detail <訂單ID>"
            exit 1
        fi

        ORDER_ID=$2
        echo -e "${GREEN}訂單詳情 (ID: $ORDER_ID)${NC}"
        psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME << EOSQL
        SELECT id, merchant_id, channel_id, channel_order_id, order_status,
               buyer_name, buyer_phone, buyer_email, total_amount, shipping_fee,
               discount_amount, payment_method, shipping_method, created_at
        FROM orders WHERE id = '$ORDER_ID' AND merchant_id = '$MERCHANT_ID';
EOSQL
        ;;

    order-status)
        if [ $# -lt 3 ]; then
            echo "用法: bash crud-tool.sh order-status <訂單ID> <新狀態>"
            echo "允許狀態: pending, confirmed, shipped, completed, cancelled"
            exit 1
        fi

        ORDER_ID=$2
        NEW_STATUS=$3

        psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME << EOSQL
        UPDATE orders SET order_status = '$NEW_STATUS', updated_at = now()
        WHERE id = '$ORDER_ID' AND merchant_id = '$MERCHANT_ID';
        SELECT '✓ 訂單狀態已更新為: $NEW_STATUS' as result;
EOSQL
        ;;

    stats)
        echo -e "${GREEN}📊 統計數據 (商家: $MERCHANT_ID)${NC}"
        psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME << EOSQL
        SELECT
            '產品總數' as 類別,
            COUNT(*)::text as 數值
        FROM product WHERE merchant_id = '$MERCHANT_ID'
        UNION ALL
        SELECT '訂單總數', COUNT(*)::text FROM orders WHERE merchant_id = '$MERCHANT_ID'
        UNION ALL
        SELECT '帳號總數', COUNT(*)::text FROM account WHERE merchant_id = '$MERCHANT_ID'
        UNION ALL
        SELECT '待處理訂單', COUNT(*)::text FROM orders WHERE merchant_id = '$MERCHANT_ID' AND order_status = 'pending';
EOSQL

        echo -e "\n${BLUE}訂單狀態分佈:${NC}"
        psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME << EOSQL
        SELECT order_status as 狀態, COUNT(*) as 數量
        FROM orders WHERE merchant_id = '$MERCHANT_ID'
        GROUP BY order_status ORDER BY COUNT(*) DESC;
EOSQL
        ;;

    *)
        if [ -n "$1" ]; then
            echo -e "${RED}✗ 未知命令: $1${NC}"
        fi
        print_help
        ;;
esac
