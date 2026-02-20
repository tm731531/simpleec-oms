#!/usr/bin/env python3
"""
SimpleEC OMS 簡單 CRUD 工具
基於資料庫為 a00000 商家執行操作
"""

import psycopg2
import json
from datetime import datetime
import sys

class OOMSDB:
    def __init__(self, merchant_id='a00000'):
        self.merchant_id = merchant_id
        self.conn = psycopg2.connect(
            host='localhost',
            port=5433,
            database='simpleec',
            user='simpleec',
            password='simpleec123'
        )
        self.cursor = self.conn.cursor()
        print(f"✓ 已連接資料庫，商家: {merchant_id}")

    def close(self):
        self.cursor.close()
        self.conn.close()

    # ========== MERCHANT ==========
    def get_merchant(self):
        """取得商家資訊"""
        self.cursor.execute(
            "SELECT * FROM merchant WHERE id = %s",
            (self.merchant_id,)
        )
        cols = [desc[0] for desc in self.cursor.description]
        row = self.cursor.fetchone()
        if row:
            return dict(zip(cols, row))
        return None

    def update_merchant(self, **kwargs):
        """更新商家資訊"""
        allowed_fields = ['merchant_name', 'merchant_email', 'merchant_phone_number',
                         'address_city', 'address_region', 'address_line1']
        updates = {k: v for k, v in kwargs.items() if k in allowed_fields}
        if not updates:
            print("沒有有效的欄位更新")
            return False

        set_clause = ', '.join([f"{k} = %s" for k in updates.keys()])
        values = list(updates.values()) + [self.merchant_id]
        self.cursor.execute(
            f"UPDATE merchant SET {set_clause}, updated_at = now() WHERE id = %s",
            values
        )
        self.conn.commit()
        print(f"✓ 已更新商家資訊")
        return True

    # ========== ACCOUNT ==========
    def list_accounts(self):
        """列出所有帳號"""
        self.cursor.execute(
            "SELECT id, account_name, account_email, access_level, status, created_at FROM account WHERE merchant_id = %s ORDER BY created_at DESC",
            (self.merchant_id,)
        )
        cols = [desc[0] for desc in self.cursor.description]
        rows = self.cursor.fetchall()
        return [dict(zip(cols, row)) for row in rows]

    def add_account(self, account_id, account_name, account_email, account_password, access_level=0):
        """新增帳號"""
        try:
            self.cursor.execute(
                """INSERT INTO account (id, account_name, account_email, account_password,
                   access_level, merchant_id, status, is_main_account)
                   VALUES (%s, %s, %s, %s, %s, %s, 'enable', false)""",
                (account_id, account_name, account_email, account_password, access_level, self.merchant_id)
            )
            self.conn.commit()
            print(f"✓ 已新增帳號: {account_id}")
            return True
        except Exception as e:
            self.conn.rollback()
            print(f"✗ 錯誤: {e}")
            return False

    # ========== PRODUCT ==========
    def list_products(self, limit=10):
        """列出產品"""
        self.cursor.execute(
            """SELECT id, sku, name, cost_price, suggest_price, quantity,
                      safety_quantity, status, created_at FROM product
               WHERE merchant_id = %s ORDER BY created_at DESC LIMIT %s""",
            (self.merchant_id, limit)
        )
        cols = [desc[0] for desc in self.cursor.description]
        rows = self.cursor.fetchall()
        return [dict(zip(cols, row)) for row in rows]

    def add_product(self, product_id, sku, name, cost_price, suggest_price, quantity=0):
        """新增產品"""
        try:
            self.cursor.execute(
                """INSERT INTO product (id, merchant_id, sku, name, cost_price,
                   suggest_price, quantity, safety_quantity, status)
                   VALUES (%s, %s, %s, %s, %s, %s, %s, 0, 'active')""",
                (product_id, self.merchant_id, sku, name, cost_price, suggest_price, quantity)
            )
            self.conn.commit()
            print(f"✓ 已新增產品: {sku} - {name}")
            return True
        except Exception as e:
            self.conn.rollback()
            print(f"✗ 錯誤: {e}")
            return False

    def update_product_quantity(self, product_id, quantity):
        """更新產品庫存"""
        self.cursor.execute(
            "UPDATE product SET quantity = %s, updated_at = now() WHERE id = %s AND merchant_id = %s",
            (quantity, product_id, self.merchant_id)
        )
        self.conn.commit()
        print(f"✓ 已更新產品庫存")
        return True

    # ========== ORDER ==========
    def list_orders(self, status=None, limit=10):
        """列出訂單"""
        if status:
            self.cursor.execute(
                """SELECT id, channel_id, channel_order_id, order_status, buyer_name,
                          total_amount, created_at FROM orders
                   WHERE merchant_id = %s AND order_status = %s ORDER BY created_at DESC LIMIT %s""",
                (self.merchant_id, status, limit)
            )
        else:
            self.cursor.execute(
                """SELECT id, channel_id, channel_order_id, order_status, buyer_name,
                          total_amount, created_at FROM orders
                   WHERE merchant_id = %s ORDER BY created_at DESC LIMIT %s""",
                (self.merchant_id, limit)
            )
        cols = [desc[0] for desc in self.cursor.description]
        rows = self.cursor.fetchall()
        return [dict(zip(cols, row)) for row in rows]

    def get_order_detail(self, order_id):
        """取得訂單詳情"""
        self.cursor.execute(
            "SELECT * FROM orders WHERE id = %s AND merchant_id = %s",
            (order_id, self.merchant_id)
        )
        cols = [desc[0] for desc in self.cursor.description]
        row = self.cursor.fetchone()
        if row:
            return dict(zip(cols, row))
        return None

    def update_order_status(self, order_id, new_status):
        """更新訂單狀態"""
        valid_statuses = ['pending', 'confirmed', 'shipped', 'completed', 'cancelled']
        if new_status not in valid_statuses:
            print(f"✗ 無效的狀態: {new_status}. 允許值: {valid_statuses}")
            return False

        self.cursor.execute(
            "UPDATE orders SET order_status = %s, updated_at = now() WHERE id = %s AND merchant_id = %s",
            (new_status, order_id, self.merchant_id)
        )
        self.conn.commit()
        print(f"✓ 訂單狀態已更新為: {new_status}")
        return True


def print_table(data, title=""):
    """簡單的表格列印"""
    if not data:
        print("  (無資料)")
        return

    if title:
        print(f"\n{title}")

    if isinstance(data, list):
        if len(data) == 0:
            print("  (無資料)")
            return

        # 列印表頭
        headers = list(data[0].keys())
        print("  " + " | ".join([h[:15].ljust(15) for h in headers]))
        print("  " + "-" * (len(headers) * 18))

        # 列印資料
        for row in data:
            values = [str(row.get(h, ''))[:15].ljust(15) for h in headers]
            print("  " + " | ".join(values))
    else:
        for key, value in data.items():
            print(f"  {key}: {value}")


# ========== CLI 命令 ==========
def main():
    db = OOMSDB('a00000')

    if len(sys.argv) < 2:
        print_help()
        db.close()
        return

    cmd = sys.argv[1]

    try:
        if cmd == 'merchant-info':
            merchant = db.get_merchant()
            if merchant:
                print_table(merchant, f"商家資訊 (ID: a00000)")
            else:
                print("✗ 找不到商家")

        elif cmd == 'merchant-update':
            if len(sys.argv) < 4:
                print("用法: crud-tool merchant-update <欄位> <值> [<欄位> <值>]...")
                print("例如: crud-tool merchant-update merchant_name '新商家名稱'")
                return

            updates = {}
            for i in range(2, len(sys.argv), 2):
                if i + 1 < len(sys.argv):
                    updates[sys.argv[i]] = sys.argv[i + 1]

            db.update_merchant(**updates)

        elif cmd == 'account-list':
            accounts = db.list_accounts()
            print_table(accounts, "帳號列表")

        elif cmd == 'account-add':
            if len(sys.argv) < 6:
                print("用法: crud-tool account-add <帳號ID> <帳號名稱> <郵箱> <密碼> [訪問級別]")
                print("例如: crud-tool account-add ACC0002 '編輯員' 'editor@a00000.com' 'pass123' 1")
                return

            account_id = sys.argv[2]
            account_name = sys.argv[3]
            account_email = sys.argv[4]
            account_password = sys.argv[5]
            access_level = int(sys.argv[6]) if len(sys.argv) > 6 else 0

            db.add_account(account_id, account_name, account_email, account_password, access_level)

        elif cmd == 'product-list':
            products = db.list_products()
            print_table(products, "產品列表")

        elif cmd == 'product-add':
            if len(sys.argv) < 7:
                print("用法: crud-tool product-add <產品ID> <SKU> <名稱> <成本價> <建議價> [數量]")
                print("例如: crud-tool product-add PROD001 'SKU001' '無線耳機' 500 1299 10")
                return

            product_id = sys.argv[2]
            sku = sys.argv[3]
            name = sys.argv[4]
            cost_price = float(sys.argv[5])
            suggest_price = float(sys.argv[6])
            quantity = int(sys.argv[7]) if len(sys.argv) > 7 else 0

            db.add_product(product_id, sku, name, cost_price, suggest_price, quantity)

        elif cmd == 'order-list':
            status = sys.argv[2] if len(sys.argv) > 2 else None
            orders = db.list_orders(status=status)
            print_table(orders, f"訂單列表 {f'(狀態: {status})' if status else ''}")

        elif cmd == 'order-detail':
            if len(sys.argv) < 3:
                print("用法: crud-tool order-detail <訂單ID>")
                return

            order_id = sys.argv[2]
            order = db.get_order_detail(order_id)
            if order:
                print_table(order, f"訂單詳情 (ID: {order_id})")
            else:
                print(f"✗ 找不到訂單: {order_id}")

        elif cmd == 'order-status-update':
            if len(sys.argv) < 4:
                print("用法: crud-tool order-status-update <訂單ID> <新狀態>")
                print("狀態: pending, confirmed, shipped, completed, cancelled")
                return

            order_id = sys.argv[2]
            new_status = sys.argv[3]
            db.update_order_status(order_id, new_status)

        elif cmd == 'stats':
            print("\n📊 統計數據")

            # 產品統計
            db.cursor.execute("SELECT COUNT(*) as count FROM product WHERE merchant_id = %s", (db.merchant_id,))
            product_count = db.cursor.fetchone()[0]
            print(f"  產品總數: {product_count}")

            # 訂單統計
            db.cursor.execute("SELECT COUNT(*) as count FROM orders WHERE merchant_id = %s", (db.merchant_id,))
            order_count = db.cursor.fetchone()[0]
            print(f"  訂單總數: {order_count}")

            # 帳號統計
            db.cursor.execute("SELECT COUNT(*) as count FROM account WHERE merchant_id = %s", (db.merchant_id,))
            account_count = db.cursor.fetchone()[0]
            print(f"  帳號總數: {account_count}")

            # 訂單狀態統計
            db.cursor.execute("""SELECT order_status, COUNT(*) as count FROM orders
                               WHERE merchant_id = %s GROUP BY order_status""", (db.merchant_id,))
            print("\n  訂單狀態分佈:")
            for status, count in db.cursor.fetchall():
                print(f"    {status}: {count}")

        else:
            print(f"✗ 未知命令: {cmd}")
            print_help()

    finally:
        db.close()


def print_help():
    print("""
SimpleEC OMS CRUD 工具 - a00000 商家
用法: python3 crud-tool.py <命令> [參數...]

📋 商家命令:
  merchant-info                                    - 查看商家資訊
  merchant-update <欄位> <值>                      - 更新商家資訊

👤 帳號命令:
  account-list                                     - 列出所有帳號
  account-add <帳號ID> <名稱> <郵箱> <密碼>       - 新增帳號

📦 產品命令:
  product-list                                     - 列出產品
  product-add <ID> <SKU> <名稱> <成本> <建議價>  - 新增產品

📋 訂單命令:
  order-list [狀態]                                - 列出訂單（可選狀態過濾）
  order-detail <訂單ID>                            - 查看訂單詳情
  order-status-update <訂單ID> <新狀態>           - 更新訂單狀態

📊 其他:
  stats                                            - 查看統計數據

例子:
  python3 crud-tool.py merchant-info
  python3 crud-tool.py account-list
  python3 crud-tool.py product-list
  python3 crud-tool.py order-list pending
  python3 crud-tool.py stats
    """)


if __name__ == '__main__':
    main()
