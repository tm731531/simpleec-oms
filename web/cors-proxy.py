#!/usr/bin/env python3
"""
SimpleEC OMS CORS Proxy
用於解決前端與後端 API 的 CORS 問題
"""

from http.server import HTTPServer, BaseHTTPRequestHandler
import json
import urllib.request
import urllib.error
from urllib.parse import urlparse, parse_qs
import sys

class CORSProxyHandler(BaseHTTPRequestHandler):
    # 後端 API 基礎 URL (指向 Mock API 以提供示例數據)
    API_BASE_URL = "http://localhost:8091"

    def do_GET(self):
        """處理 GET 請求"""
        try:
            # 解析請求路徑
            if self.path.startswith('/api/'):
                # 轉發到後端 API
                target_url = self.API_BASE_URL + self.path

                # 發送請求到後端
                response = urllib.request.urlopen(target_url, timeout=5)
                status_code = response.status
                body = response.read()

                # 返回響應，包含 CORS 頭
                self.send_response(status_code)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Access-Control-Allow-Origin', '*')
                self.send_header('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS')
                self.send_header('Access-Control-Allow-Headers', 'Content-Type')
                self.end_headers()

                self.wfile.write(body)
            else:
                # 返回 404
                self.send_response(404)
                self.end_headers()

        except urllib.error.URLError as e:
            self.send_response(502)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            response = {'error': f'無法連接到後端 API: {str(e)}'}
            self.wfile.write(json.dumps(response).encode())
        except Exception as e:
            self.send_response(500)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            response = {'error': str(e)}
            self.wfile.write(json.dumps(response).encode())

    def do_OPTIONS(self):
        """處理 OPTIONS 請求（CORS 預檢）"""
        self.send_response(200)
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')
        self.end_headers()

    def do_POST(self):
        """處理 POST 請求"""
        try:
            if self.path.startswith('/api/'):
                # 讀取請求體
                content_length = int(self.headers.get('Content-Length', 0))
                body = self.rfile.read(content_length)

                # 轉發到後端 API
                target_url = self.API_BASE_URL + self.path

                req = urllib.request.Request(
                    target_url,
                    data=body,
                    headers={'Content-Type': 'application/json'}
                )

                response = urllib.request.urlopen(req, timeout=5)
                status_code = response.status
                response_body = response.read()

                # 返回響應
                self.send_response(status_code)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Access-Control-Allow-Origin', '*')
                self.send_header('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS')
                self.send_header('Access-Control-Allow-Headers', 'Content-Type')
                self.end_headers()

                self.wfile.write(response_body)
            else:
                self.send_response(404)
                self.end_headers()

        except Exception as e:
            self.send_response(500)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            response = {'error': str(e)}
            self.wfile.write(json.dumps(response).encode())

    def log_message(self, format, *args):
        """抑制日誌輸出"""
        pass

def run_proxy(port=8090):
    """啟動 CORS 代理服務器"""
    server_address = ('', port)
    httpd = HTTPServer(server_address, CORSProxyHandler)
    print(f'✓ CORS 代理已啟動 http://localhost:{port}')
    print(f'✓ 轉發到後端 API: {CORSProxyHandler.API_BASE_URL}')
    httpd.serve_forever()

if __name__ == '__main__':
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8090
    run_proxy(port)
