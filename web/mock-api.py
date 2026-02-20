#!/usr/bin/env python3
"""
SimpleEC OMS Mock API Server
提供逼真的示例數據用於前端測試
"""

from http.server import HTTPServer, BaseHTTPRequestHandler
import json
from datetime import datetime, timedelta
import random

class MockAPIHandler(BaseHTTPRequestHandler):

    def do_GET(self):
        """處理 GET 請求"""
        if self.path == '/api/orders':
            self.send_orders()
        elif self.path.startswith('/api/orders?'):
            self.send_orders()
        elif self.path == '/api/stats':
            self.send_stats()
        elif self.path == '/api/health':
            self.send_health()
        else:
            self.send_error_response(404, 'Endpoint not found')

    def do_OPTIONS(self):
        """處理 CORS 預檢請求"""
        self.send_response(200)
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')
        self.end_headers()

    def send_json_response(self, data, status=200):
        """發送 JSON 響應"""
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()
        self.wfile.write(json.dumps(data, ensure_ascii=False).encode('utf-8'))

    def send_error_response(self, status, message):
        """發送錯誤響應"""
        self.send_json_response({'error': message, 'status': status}, status)

    def send_health(self):
        """健康檢查端點"""
        self.send_json_response({
            'status': 'UP',
            'service': 'SimpleEC OMS Mock API',
            'timestamp': datetime.now().isoformat()
        })

    def send_stats(self):
        """統計數據端點"""
        self.send_json_response({
            'dailyOrders': 125,
            'pendingOrders': 8,
            'weeklyShipments': 342,
            'totalRevenue': 125650.50,
            'growth': '15%'
        })

    def send_orders(self):
        """訂單列表端點"""
        orders = [
            {
                'id': 'ORD20260220001',
                'customerId': 'CUST001',
                'customerName': '王小明',
                'platform': 'MOMO',
                'amount': 1299.00,
                'status': 'pending',
                'createTime': (datetime.now() - timedelta(hours=2)).isoformat(),
                'items': [
                    {'sku': 'SKU001', 'name': '無線藍芽耳機', 'qty': 1, 'price': 1299.00}
                ]
            },
            {
                'id': 'ORD20260220002',
                'customerId': 'CUST002',
                'customerName': '李美麗',
                'platform': 'Shopee',
                'amount': 2499.00,
                'status': 'confirmed',
                'createTime': (datetime.now() - timedelta(hours=5)).isoformat(),
                'items': [
                    {'sku': 'SKU002', 'name': '智能手錶', 'qty': 1, 'price': 2499.00}
                ]
            },
            {
                'id': 'ORD20260220003',
                'customerId': 'CUST003',
                'customerName': '張三丰',
                'platform': 'Yahoo',
                'amount': 599.00,
                'status': 'shipped',
                'createTime': (datetime.now() - timedelta(hours=12)).isoformat(),
                'items': [
                    {'sku': 'SKU003', 'name': '手機殼', 'qty': 2, 'price': 299.50}
                ]
            },
            {
                'id': 'ORD20260220004',
                'customerId': 'CUST004',
                'customerName': '陈晓红',
                'platform': 'PChome',
                'amount': 1899.00,
                'status': 'completed',
                'createTime': (datetime.now() - timedelta(days=2)).isoformat(),
                'items': [
                    {'sku': 'SKU004', 'name': '平板電腦', 'qty': 1, 'price': 1899.00}
                ]
            },
            {
                'id': 'ORD20260220005',
                'customerId': 'CUST005',
                'customerName': '林小平',
                'platform': 'MOMO',
                'amount': 3599.00,
                'status': 'pending',
                'createTime': (datetime.now() - timedelta(hours=1)).isoformat(),
                'items': [
                    {'sku': 'SKU005', 'name': '筆記本電腦', 'qty': 1, 'price': 3599.00}
                ]
            }
        ]
        self.send_json_response(orders)

    def log_message(self, format, *args):
        """抑制日誌輸出"""
        pass


def run_mock_api(port=8091):
    """啟動 Mock API 服務器"""
    server_address = ('', port)
    httpd = HTTPServer(server_address, MockAPIHandler)
    print(f'✓ Mock API 已啟動: http://localhost:{port}')
    httpd.serve_forever()


if __name__ == '__main__':
    import sys
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8091
    run_mock_api(port)
