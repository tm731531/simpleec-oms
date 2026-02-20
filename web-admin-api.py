#!/usr/bin/env python3
"""
SimpleEC OMS 簡單Web CRUD API
基於資料庫為 a00000 商家提供 CRUD API
"""

from http.server import HTTPServer, BaseHTTPRequestHandler
import json
import sqlite3
import subprocess
import sys
from urllib.parse import parse_qs, urlparse
from datetime import datetime

# 資料庫連接設定
DB_CONFIG = {
    'host': 'localhost',
    'port': '5433',
    'database': 'simpleec',
    'user': 'simpleec',
    'password': 'simpleec123'
}

MERCHANT_ID = 'a00000'

class DBHelper:
    """資料庫幫助類"""

    @staticmethod
    def execute_query(query, params=None):
        """執行SELECT查詢"""
        cmd = f'PGPASSWORD={DB_CONFIG["password"]} psql -h {DB_CONFIG["host"]} -p {DB_CONFIG["port"]} -U {DB_CONFIG["user"]} -d {DB_CONFIG["database"]} -tc'

        if params:
            # 轉義參數
            for key, value in params.items():
                query = query.replace(f'%{key}%', f"'{str(value).replace(chr(39), chr(39)*2)}'")

        try:
            result = subprocess.run([cmd], input=query, shell=True, capture_output=True, text=True, timeout=5)
            if result.returncode != 0:
                return None

            lines = result.stdout.strip().split('\n')
            data = []
            for line in lines:
                if line.strip():
                    cols = [col.strip() for col in line.split('|')]
                    data.append(cols)
            return data
        except Exception as e:
            print(f"Error: {e}")
            return None

    @staticmethod
    def execute_update(query, params=None):
        """執行INSERT/UPDATE/DELETE操作"""
        cmd = f'PGPASSWORD={DB_CONFIG["password"]} psql -h {DB_CONFIG["host"]} -p {DB_CONFIG["port"]} -U {DB_CONFIG["user"]} -d {DB_CONFIG["database"]}'

        if params:
            for key, value in params.items():
                query = query.replace(f'%{key}%', f"'{str(value).replace(chr(39), chr(39)*2)}'")

        try:
            result = subprocess.run([cmd], input=query, shell=True, capture_output=True, text=True, timeout=5)
            return result.returncode == 0
        except Exception as e:
            print(f"Error: {e}")
            return False


class CRUDAPIHandler(BaseHTTPRequestHandler):

    def send_json(self, data, status=200):
        """發送JSON響應"""
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')
        self.end_headers()
        self.wfile.write(json.dumps(data, ensure_ascii=False).encode('utf-8'))

    def do_OPTIONS(self):
        """處理CORS預檢"""
        self.send_response(200)
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')
        self.end_headers()

    def do_GET(self):
        """處理GET請求"""
        parsed_path = urlparse(self.path)
        path = parsed_path.path
        query_params = parse_qs(parsed_path.query)

        if path == '/api/merchant':
            self.get_merchant()
        elif path == '/api/accounts':
            self.list_accounts()
        elif path == '/api/products':
            self.list_products()
        elif path == '/api/orders':
            self.list_orders()
        elif path == '/api/stats':
            self.get_stats()
        else:
            self.send_json({'error': 'Not found'}, 404)

    def do_POST(self):
        """處理POST請求"""
        parsed_path = urlparse(self.path)
        path = parsed_path.path

        content_length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(content_length).decode('utf-8') if content_length > 0 else '{}'
        data = json.loads(body) if body else {}

        if path == '/api/accounts':
            self.create_account(data)
        elif path == '/api/products':
            self.create_product(data)
        elif path == '/api/merchant':
            self.update_merchant(data)
        else:
            self.send_json({'error': 'Not found'}, 404)

    def do_PUT(self):
        """處理PUT請求"""
        parsed_path = urlparse(self.path)
        path = parsed_path.path

        content_length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(content_length).decode('utf-8') if content_length > 0 else '{}'
        data = json.loads(body) if body else {}

        if path.startswith('/api/accounts/'):
            account_id = path.split('/')[-1]
            self.update_account(account_id, data)
        elif path.startswith('/api/products/'):
            product_id = path.split('/')[-1]
            self.update_product(product_id, data)
        elif path.startswith('/api/orders/'):
            order_id = path.split('/')[-1]
            self.update_order(order_id, data)
        else:
            self.send_json({'error': 'Not found'}, 404)

    def do_DELETE(self):
        """處理DELETE請求"""
        parsed_path = urlparse(self.path)
        path = parsed_path.path

        if path.startswith('/api/accounts/'):
            account_id = path.split('/')[-1]
            self.delete_account(account_id)
        elif path.startswith('/api/products/'):
            product_id = path.split('/')[-1]
            self.delete_product(product_id)
        else:
            self.send_json({'error': 'Not found'}, 404)

    # ===== Merchant =====
    def get_merchant(self):
        """獲取商家信息"""
        query = f"SELECT id, merchant_name, merchant_email, merchant_phone_number, tax_id_number, address_city, address_region, status, created_at FROM merchant WHERE id = '{MERCHANT_ID}';"
        result = subprocess.run(
            f'PGPASSWORD={DB_CONFIG["password"]} psql -h {DB_CONFIG["host"]} -p {DB_CONFIG["port"]} -U {DB_CONFIG["user"]} -d {DB_CONFIG["database"]} -tA',
            input=query, shell=True, capture_output=True, text=True
        )

        if result.returncode == 0 and result.stdout.strip():
            cols = ['id', 'merchant_name', 'merchant_email', 'merchant_phone_number', 'tax_id_number', 'address_city', 'address_region', 'status', 'created_at']
            values = result.stdout.strip().split('|')
            merchant = dict(zip(cols, values))
            self.send_json(merchant)
        else:
            self.send_json({'error': 'Merchant not found'}, 404)

    def update_merchant(self, data):
        """更新商家信息"""
        allowed_fields = ['merchant_name', 'merchant_email', 'merchant_phone_number', 'address_city', 'address_region', 'address_line1']
        updates = {k: v for k, v in data.items() if k in allowed_fields}

        if not updates:
            self.send_json({'error': 'No valid fields to update'}, 400)
            return

        set_clause = ', '.join([f"{k} = '{str(v).replace(chr(39), chr(39)*2)}'" for k, v in updates.items()])
        query = f"UPDATE merchant SET {set_clause}, updated_at = now() WHERE id = '{MERCHANT_ID}';"

        if DBHelper.execute_update(query):
            self.send_json({'success': True, 'message': 'Merchant updated'})
        else:
            self.send_json({'error': 'Update failed'}, 500)

    # ===== Accounts =====
    def list_accounts(self):
        """列出所有帳號"""
        query = f"SELECT id, account_name, account_email, access_level, status, is_main_account, created_at FROM account WHERE merchant_id = '{MERCHANT_ID}' ORDER BY created_at DESC;"
        result = subprocess.run(
            f'PGPASSWORD={DB_CONFIG["password"]} psql -h {DB_CONFIG["host"]} -p {DB_CONFIG["port"]} -U {DB_CONFIG["user"]} -d {DB_CONFIG["database"]} -tA -F|',
            input=query, shell=True, capture_output=True, text=True
        )

        accounts = []
        if result.returncode == 0:
            cols = ['id', 'account_name', 'account_email', 'access_level', 'status', 'is_main_account', 'created_at']
            for line in result.stdout.strip().split('\n'):
                if line.strip():
                    values = line.split('|')
                    if len(values) == len(cols):
                        accounts.append(dict(zip(cols, values)))

        self.send_json(accounts)

    def create_account(self, data):
        """新增帳號"""
        required = ['id', 'account_name', 'account_email', 'account_password']
        if not all(k in data for k in required):
            self.send_json({'error': 'Missing required fields'}, 400)
            return

        query = f"""INSERT INTO account (id, account_name, account_email, account_password, access_level, merchant_id, status, is_main_account)
                   VALUES ('{data["id"]}', '{data["account_name"]}', '{data["account_email"]}', '{data["account_password"]}', 0, '{MERCHANT_ID}', 'enable', false);"""

        if DBHelper.execute_update(query):
            self.send_json({'success': True, 'message': 'Account created'})
        else:
            self.send_json({'error': 'Account creation failed'}, 500)

    def update_account(self, account_id, data):
        """更新帳號"""
        allowed = ['account_name', 'account_email', 'access_level', 'status']
        updates = {k: v for k, v in data.items() if k in allowed}

        if not updates:
            self.send_json({'error': 'No valid fields to update'}, 400)
            return

        set_clause = ', '.join([f"{k} = '{str(v).replace(chr(39), chr(39)*2)}'" for k, v in updates.items()])
        query = f"UPDATE account SET {set_clause} WHERE id = '{account_id}' AND merchant_id = '{MERCHANT_ID}';"

        if DBHelper.execute_update(query):
            self.send_json({'success': True})
        else:
            self.send_json({'error': 'Update failed'}, 500)

    def delete_account(self, account_id):
        """刪除帳號"""
        query = f"DELETE FROM account WHERE id = '{account_id}' AND merchant_id = '{MERCHANT_ID}';"
        if DBHelper.execute_update(query):
            self.send_json({'success': True})
        else:
            self.send_json({'error': 'Delete failed'}, 500)

    # ===== Products =====
    def list_products(self):
        """列出所有產品"""
        query = f"SELECT id, sku, name, cost_price, suggest_price, quantity, status, created_at FROM product WHERE merchant_id = '{MERCHANT_ID}' ORDER BY created_at DESC LIMIT 100;"
        result = subprocess.run(
            f'PGPASSWORD={DB_CONFIG["password"]} psql -h {DB_CONFIG["host"]} -p {DB_CONFIG["port"]} -U {DB_CONFIG["user"]} -d {DB_CONFIG["database"]} -tA -F|',
            input=query, shell=True, capture_output=True, text=True
        )

        products = []
        if result.returncode == 0:
            cols = ['id', 'sku', 'name', 'cost_price', 'suggest_price', 'quantity', 'status', 'created_at']
            for line in result.stdout.strip().split('\n'):
                if line.strip():
                    values = line.split('|')
                    if len(values) == len(cols):
                        products.append(dict(zip(cols, values)))

        self.send_json(products)

    def create_product(self, data):
        """新增產品"""
        required = ['id', 'sku', 'name', 'cost_price', 'suggest_price']
        if not all(k in data for k in required):
            self.send_json({'error': 'Missing required fields'}, 400)
            return

        quantity = data.get('quantity', 0)
        query = f"""INSERT INTO product (id, merchant_id, sku, name, cost_price, suggest_price, quantity, status)
                   VALUES ('{data["id"]}', '{MERCHANT_ID}', '{data["sku"]}', '{data["name"]}', {data["cost_price"]}, {data["suggest_price"]}, {quantity}, 'active');"""

        if DBHelper.execute_update(query):
            self.send_json({'success': True})
        else:
            self.send_json({'error': 'Product creation failed'}, 500)

    def update_product(self, product_id, data):
        """更新產品"""
        allowed = ['name', 'cost_price', 'suggest_price', 'quantity', 'status']
        updates = {k: v for k, v in data.items() if k in allowed}

        if not updates:
            self.send_json({'error': 'No valid fields'}, 400)
            return

        set_clause = ', '.join([f"{k} = {v if k in ['cost_price', 'suggest_price', 'quantity'] else f'\'{v}\''}" for k, v in updates.items()])
        query = f"UPDATE product SET {set_clause} WHERE id = '{product_id}' AND merchant_id = '{MERCHANT_ID}';"

        if DBHelper.execute_update(query):
            self.send_json({'success': True})
        else:
            self.send_json({'error': 'Update failed'}, 500)

    def delete_product(self, product_id):
        """刪除產品"""
        query = f"DELETE FROM product WHERE id = '{product_id}' AND merchant_id = '{MERCHANT_ID}';"
        if DBHelper.execute_update(query):
            self.send_json({'success': True})
        else:
            self.send_json({'error': 'Delete failed'}, 500)

    # ===== Orders =====
    def list_orders(self):
        """列出訂單"""
        query = f"SELECT id, channel_id, channel_order_id, order_status, buyer_name, total_amount, created_at FROM orders WHERE merchant_id = '{MERCHANT_ID}' ORDER BY created_at DESC LIMIT 100;"
        result = subprocess.run(
            f'PGPASSWORD={DB_CONFIG["password"]} psql -h {DB_CONFIG["host"]} -p {DB_CONFIG["port"]} -U {DB_CONFIG["user"]} -d {DB_CONFIG["database"]} -tA -F|',
            input=query, shell=True, capture_output=True, text=True
        )

        orders = []
        if result.returncode == 0:
            cols = ['id', 'channel_id', 'channel_order_id', 'order_status', 'buyer_name', 'total_amount', 'created_at']
            for line in result.stdout.strip().split('\n'):
                if line.strip():
                    values = line.split('|')
                    if len(values) == len(cols):
                        orders.append(dict(zip(cols, values)))

        self.send_json(orders)

    def update_order(self, order_id, data):
        """更新訂單狀態"""
        if 'order_status' not in data:
            self.send_json({'error': 'Missing order_status'}, 400)
            return

        valid_statuses = ['pending', 'confirmed', 'shipped', 'completed', 'cancelled']
        if data['order_status'] not in valid_statuses:
            self.send_json({'error': 'Invalid status'}, 400)
            return

        query = f"UPDATE orders SET order_status = '{data['order_status']}' WHERE id = '{order_id}' AND merchant_id = '{MERCHANT_ID}';"
        if DBHelper.execute_update(query):
            self.send_json({'success': True})
        else:
            self.send_json({'error': 'Update failed'}, 500)

    # ===== Stats =====
    def get_stats(self):
        """獲取統計數據"""
        stats = {}

        # 產品數量
        result = subprocess.run(
            f'PGPASSWORD={DB_CONFIG["password"]} psql -h {DB_CONFIG["host"]} -p {DB_CONFIG["port"]} -U {DB_CONFIG["user"]} -d {DB_CONFIG["database"]} -tc',
            input=f"SELECT COUNT(*) FROM product WHERE merchant_id = '{MERCHANT_ID}';",
            shell=True, capture_output=True, text=True
        )
        stats['product_count'] = int(result.stdout.strip()) if result.returncode == 0 else 0

        # 訂單數量
        result = subprocess.run(
            f'PGPASSWORD={DB_CONFIG["password"]} psql -h {DB_CONFIG["host"]} -p {DB_CONFIG["port"]} -U {DB_CONFIG["user"]} -d {DB_CONFIG["database"]} -tc',
            input=f"SELECT COUNT(*) FROM orders WHERE merchant_id = '{MERCHANT_ID}';",
            shell=True, capture_output=True, text=True
        )
        stats['order_count'] = int(result.stdout.strip()) if result.returncode == 0 else 0

        # 帳號數量
        result = subprocess.run(
            f'PGPASSWORD={DB_CONFIG["password"]} psql -h {DB_CONFIG["host"]} -p {DB_CONFIG["port"]} -U {DB_CONFIG["user"]} -d {DB_CONFIG["database"]} -tc',
            input=f"SELECT COUNT(*) FROM account WHERE merchant_id = '{MERCHANT_ID}';",
            shell=True, capture_output=True, text=True
        )
        stats['account_count'] = int(result.stdout.strip()) if result.returncode == 0 else 0

        self.send_json(stats)

    def log_message(self, format, *args):
        pass


def run_api_server(port=8092):
    """啟動API服務器"""
    server_address = ('', port)
    httpd = HTTPServer(server_address, CRUDAPIHandler)
    print(f'✓ CRUD API 已啟動: http://localhost:{port}')
    httpd.serve_forever()


if __name__ == '__main__':
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8092
    run_api_server(port)
