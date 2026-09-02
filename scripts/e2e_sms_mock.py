#!/usr/bin/env python3
"""开发/端到端验证用的短信供应商 mock 服务器。

配合 dev 库租户 1 的 channel_config(sms) 使用：endpoint 指向本服务
（http://127.0.0.1:8089/sms/send），app 触发真实发送后本进程打印收到的
请求头与 form body，并回一个供应商风格 JSON messageId。

启动：python3 scripts/e2e_sms_mock.py
验证：执行工作流后观察输出；delivery_record 应出现 sms-* DELIVERED。
"""
from http.server import BaseHTTPRequestHandler, HTTPServer

class H(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(length).decode('utf-8')
        print(f"[sms-mock] path={self.path} X-Api-Key={self.headers.get('X-Api-Key')} "
              f"X-Api-Secret={self.headers.get('X-Api-Secret')} body={body}", flush=True)
        resp = b'{"messageId":"e2e-sms-1"}'
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(resp)))
        self.end_headers()
        self.wfile.write(resp)

    def log_message(self, fmt, *args):
        pass

print("[sms-mock] listening on 127.0.0.1:8089", flush=True)
HTTPServer(('127.0.0.1', 8089), H).serve_forever()