#!/usr/bin/env python3
"""
SimpleEC OMS 簡單Web API - 直接連接資料庫
"""

from http.server import HTTPServer, BaseHTTPRequestHandler
import json
import subprocess
from urllib.parse import urlparse

MERCHANT_ID = 'a00000'

class SimpleAPIHandler(BaseHTTPRequestHandler):

    def send_json(self, data, status=200):
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')
        self.end_headers()
        self.wfile.write(json.dumps(data, ensure_ascii=False).encode('utf-8'))

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')
        self.end_headers()

    def do_GET(self):
        path = urlparse(self.path).path

        if path == '/api/stats':
            self.get_stats()
        elif path == '/api/merchant':
            self.get_merchant()
        elif path == '/api/accounts':
            self.get_accounts()
        elif path == '/api/products':
            self.get_products()
        elif path == '/api/orders':
            self.get_orders()
        else:
            self.send_json({'error': 'Not found'}, 404)

    def execute_sql(self, query):
        """執行SQL查詢並返回JSON結果"""
        try:
            cmd = f"PGPASSWORD=simpleec123 psql -h localhost -p 5433 -U simpleec -d simpleec --csv -c \"{query}\""
            result = subprocess.run(
                cmd,
                shell=True,
                capture_output=True,
                text=True,
                timeout=5
            )

            if result.returncode != 0:
                print(f"SQL Error: {result.stderr}")
                return []

            lines = result.stdout.strip().split('\n')
            if len(lines) < 2:
                return []

            headers = lines[0].split(',')
            data = []
            for line in lines[1:]:
                if line.strip():
                    values = line.split(',')
                    data.append(dict(zip(headers, values)))

            return data
        except Exception as e:
            print(f"Error executing SQL: {e}")
            return []

    def get_stats(self):
        """統計數據"""
        product_query = f"SELECT COUNT(*) as count FROM product WHERE merchant_id = '{MERCHANT_ID}';"
        order_query = f"SELECT COUNT(*) as count FROM orders WHERE merchant_id = '{MERCHANT_ID}';"
        account_query = f"SELECT COUNT(*) as count FROM account WHERE merchant_id = '{MERCHANT_ID}';"

        product_count = int(self.execute_sql(product_query)[0]['count']) if self.execute_sql(product_query) else 0
        order_count = int(self.execute_sql(order_query)[0]['count']) if self.execute_sql(order_query) else 0
        account_count = int(self.execute_sql(account_query)[0]['count']) if self.execute_sql(account_query) else 0

        self.send_json({
            'product_count': product_count,
            'order_count': order_count,
            'account_count': account_count
        })

    def get_merchant(self):
        """商家信息"""
        query = f"SELECT id, merchant_name, merchant_email, merchant_phone_number, tax_id_number, address_city, address_region, status FROM merchant WHERE id = '{MERCHANT_ID}';"
        result = self.execute_sql(query)
        if result:
            self.send_json(result[0])
        else:
            self.send_json({'error': 'Not found'}, 404)

    def get_accounts(self):
        """帳號列表"""
        query = f"SELECT id, account_name, account_email, access_level, status, is_main_account, created_at FROM account WHERE merchant_id = '{MERCHANT_ID}' ORDER BY created_at DESC;"
        result = self.execute_sql(query)
        self.send_json(result)

    def get_products(self):
        """產品列表"""
        query = f"SELECT id, sku, name, cost_price, suggest_price, quantity, status, created_at FROM product WHERE merchant_id = '{MERCHANT_ID}' ORDER BY created_at DESC LIMIT 100;"
        result = self.execute_sql(query)
        self.send_json(result)

    def get_orders(self):
        """訂單列表"""
        query = f"SELECT id, channel_id, channel_order_id, order_status, buyer_name, total_amount, created_at FROM orders WHERE merchant_id = '{MERCHANT_ID}' ORDER BY created_at DESC LIMIT 100;"
        result = self.execute_sql(query)
        self.send_json(result)

    def log_message(self, format, *args):
        pass


def run_server(port=8093):
    server_address = ('', port)
    httpd = HTTPServer(server_address, SimpleAPIHandler)
    print(f'✓ Simple Web API started on port {port}')
    httpd.serve_forever()


if __name__ == '__main__':
    import sys
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8093
    run_server(port)
