#!/usr/bin/env python3
"""
SimpleEC OMS — Test Data Seeder (API-based)

Uses the public REST API to inject fake orders, exercising the full event flow:
  POST /api/auth/login       → JWT token
  POST /api/user/orders      → publishes ORDER_UPSERT to order.process Kafka topic
  → OrderUpsertConsumer      → DB write + stats dirty marker
  → StatsRecalcHandler       → DailyStatisticsService recalculate()

Usage:
  docker compose run --rm simpleec-test-seeder
  docker compose run --rm simpleec-test-seeder --count 20
  docker compose run --rm simpleec-test-seeder --channel ch_shopee_001 --count 5
"""

import argparse
import json
import random
import sys
import time
from datetime import datetime, timezone, timedelta

import requests

API_BASE = "http://simpleec-api:8080"
LOGIN_EMAIL = "admin@a00000.com"
LOGIN_PASSWORD = "pass123456"
MERCHANT_ID = "m_test_001"

CHANNELS = [
    {"channelId": "ch_momo_001",   "platformId": "momo"},
    {"channelId": "ch_shopee_001", "platformId": "shopee"},
]

STATUSES = ["PENDING", "CONFIRMED", "READY_TO_SHIP", "SHIPPED", "COMPLETED", "CANCELLED"]
PAYMENT_METHODS = ["credit_card", "cod", "transfer", "convenience_store"]
SHIPPING_METHODS = ["7-11", "family_mart", "home_delivery", "post_office"]

PRODUCTS = [
    {"name": "有機燕麥片 500g",   "price": 299,  "sku": "OAT-500"},
    {"name": "天然蜂蜜 350ml",    "price": 450,  "sku": "HONEY-350"},
    {"name": "綜合堅果禮盒",       "price": 890,  "sku": "NUTS-GIFT"},
    {"name": "有機綠茶粉 100g",   "price": 320,  "sku": "GREEN-TEA-100"},
    {"name": "冷壓橄欖油 500ml",  "price": 680,  "sku": "OLIVE-500"},
    {"name": "奇亞籽 300g",       "price": 199,  "sku": "CHIA-300"},
]

BUYER_NAMES  = ["陳小明", "林美麗", "張大華", "王志偉", "李淑芬", "吳建志", "黃怡君", "劉宗翰"]
BUYER_PHONES = ["0912345678", "0923456789", "0934567890", "0945678901", "0956789012", "0967890123"]
CITIES = [
    ("台北市信義區", "松高路１號"),
    ("新北市板橋區", "中山路100號"),
    ("台中市西屯區", "台灣大道四段200號"),
    ("高雄市前鎮區", "中山二路80號"),
    ("桃園市中壢區", "中央西路一段50號"),
]


def wait_for_api(retries: int = 30, delay: float = 3.0):
    print(f"Waiting for API at {API_BASE}...", flush=True)
    for attempt in range(1, retries + 1):
        try:
            r = requests.get(f"{API_BASE}/api/health", timeout=5)
            if r.status_code == 200:
                print(f"API is ready (attempt {attempt})", flush=True)
                return
        except Exception:
            pass
        print(f"  Not ready yet (attempt {attempt}/{retries}), retrying in {delay}s...", flush=True)
        time.sleep(delay)
    print("ERROR: API not available after retries. Exiting.", flush=True)
    sys.exit(1)


def login() -> str:
    print(f"Logging in as {LOGIN_EMAIL}...", flush=True)
    r = requests.post(
        f"{API_BASE}/api/auth/login",
        json={"email": LOGIN_EMAIL, "password": LOGIN_PASSWORD},
        timeout=10,
    )
    if r.status_code != 200:
        print(f"ERROR: Login failed ({r.status_code}): {r.text}")
        sys.exit(1)
    token = r.json()["token"]
    merchant_name = r.json().get("user", {}).get("merchantName", "")
    print(f"Logged in — merchant: {merchant_name} ({MERCHANT_ID})", flush=True)
    return token


def build_order(seq: int, channel: dict, days_ago: int) -> dict:
    channel_order_id = f"{channel['platformId'].upper()}_TEST_{int(time.time())}_{seq:04d}"

    num_items = random.randint(1, 3)
    selected = random.sample(PRODUCTS, num_items)
    items = []
    total_item_amount = 0
    for i, prod in enumerate(selected):
        qty = random.randint(1, 3)
        subtotal = prod["price"] * qty
        total_item_amount += subtotal
        items.append({
            "channelItemId": f"{channel_order_id}_ITEM_{i + 1}",
            "skuCode":       prod["sku"],
            "productName":   prod["name"],
            "quantity":      qty,
            "unitPrice":     prod["price"],
            "subtotal":      subtotal,
        })

    shipping_fee = 0 if total_item_amount >= 1000 else 60
    discount = 100 if total_item_amount >= 2000 else 0
    total_amount = total_item_amount + shipping_fee - discount

    created_at = (
        datetime.now(timezone.utc)
        - timedelta(days=days_ago, minutes=random.randint(0, 1440))
    )

    buyer_name  = random.choice(BUYER_NAMES)
    buyer_phone = random.choice(BUYER_PHONES)
    city, street = random.choice(CITIES)

    return {
        "merchantId":       MERCHANT_ID,
        "channelId":        channel["channelId"],
        "channelOrderId":   channel_order_id,
        "orderStatus":      random.choice(STATUSES),
        "totalAmount":      total_amount,
        "shippingFee":      shipping_fee,
        "discountAmount":   discount,
        "paymentMethod":    random.choice(PAYMENT_METHODS),
        "shippingMethod":   random.choice(SHIPPING_METHODS),
        "channelCreatedAt": created_at.strftime("%Y-%m-%dT%H:%M:%S"),
        "items":            items,
        "buyerName":        buyer_name,
        "buyerPhone":       buyer_phone,
        "buyerEmail":       f"buyer{seq:04d}@example.com",
        "shippingAddress":  f"{city}{street}",
        "isRollback":       False,
        "hasRefund":        False,
    }


def main():
    parser = argparse.ArgumentParser(description="SimpleEC OMS test data seeder (REST API)")
    parser.add_argument("--count",       type=int, default=15,
                        help="Total orders to generate (default: 15)")
    parser.add_argument("--channel",     type=str, default=None,
                        help="Specific channelId (default: alternates momo/shopee)")
    parser.add_argument("--days-spread", type=int, default=3,
                        help="Spread orders over last N days (default: 3)")
    args = parser.parse_args()

    wait_for_api()
    token = login()
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}

    target_channels = CHANNELS
    if args.channel:
        matched = [c for c in CHANNELS if c["channelId"] == args.channel]
        if not matched:
            print(f"ERROR: Unknown channel '{args.channel}'. Valid: {[c['channelId'] for c in CHANNELS]}")
            sys.exit(1)
        target_channels = matched

    print(f"\nSending {args.count} orders via POST /api/orders ...\n", flush=True)

    sent = 0
    errors = 0
    created_ids = []

    for i in range(args.count):
        channel = target_channels[i % len(target_channels)]
        days_ago = random.randint(0, args.days_spread)
        order = build_order(seq=i + 1, channel=channel, days_ago=days_ago)

        try:
            r = requests.post(
                f"{API_BASE}/api/user/orders",
                json=order,
                headers=headers,
                timeout=10,
            )
            if r.status_code == 202:
                channel_order_id = r.json().get("channelOrderId", "?")
                created_ids.append(channel_order_id)
                print(
                    f"  [{i+1:3d}/{args.count}] ✓ {channel['channelId']:20s} | "
                    f"{order['orderStatus']:15s} | NT${order['totalAmount']:>7} | "
                    f"→ order.process queued",
                    flush=True,
                )
                sent += 1
            else:
                print(
                    f"  [{i+1:3d}/{args.count}] ✗ HTTP {r.status_code}: {r.text[:120]}",
                    flush=True,
                )
                errors += 1
        except Exception as e:
            print(f"  [{i+1:3d}/{args.count}] ✗ FAILED: {e}", flush=True)
            errors += 1

        time.sleep(0.05)

    print(f"\n{'='*70}")
    print(f"Done: {sent} sent, {errors} errors")
    print(f"{'='*70}")
    print(f"\nVerify with:")
    print(f"  docker exec -it simpleec-postgres psql -U simpleec -c \\")
    print(f"    \"SELECT channel_id, order_status, COUNT(*) FROM orders")
    print(f"     WHERE merchant_id='{MERCHANT_ID}' GROUP BY 1,2 ORDER BY 1,2;\"")
    print(f"\nQuery recent orders:")
    print(f"  curl -s -H 'Authorization: Bearer <token>' \\")
    print(f"    '{API_BASE}/api/orders?merchantId={MERCHANT_ID}&size=5' | jq '.content[].id'")


if __name__ == "__main__":
    main()
