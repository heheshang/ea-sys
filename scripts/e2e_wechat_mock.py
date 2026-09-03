#!/usr/bin/env python3
"""开发/端到端验证用的微信模板消息 API mock 服务器。

配合 dev 库租户 1 的 channel_config(wechat) 使用：endpoint 指向本服务
（http://127.0.0.1:8090），appId/appSecret/templateId 任意非空字符串即可。
app 触发真实发送后本进程打印 token 与模板消息请求，并回微信风格响应。

启动：python3 scripts/e2e_wechat_mock.py
验证：执行工作流后观察输出；delivery_record 应出现 wechat-* DELIVERED。

行为与 ChannelConfigTests 内联 mock 一致：
- GET /cgi-bin/token → {"access_token":"mock-token-abc","expires_in":7200}
  （expires_in 后必须有逗号：适配器按逗号截取，缺省 7200s 也能兜底）
- POST /cgi-bin/message/template/send → 成功 {"errcode":0,"errmsg":"ok"}；
  body 含「触发失败内容」时返回 {"errcode":40001,"errmsg":"invalid credential"}
  （适配器以 errcode==0 判定成功，HTTP 2xx 不代表成功）
"""
import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer

TOKEN_RESP = json.dumps({"access_token": "mock-token-abc", "expires_in": 7200}).encode()
OK = json.dumps({"errcode": 0, "errmsg": "ok"}).encode()
ERR = json.dumps({"errcode": 40001, "errmsg": "invalid credential"}).encode()


class H(BaseHTTPRequestHandler):
    def _reply(self, resp: bytes):
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(resp)))
        self.end_headers()
        self.wfile.write(resp)

    def do_GET(self):
        if self.path.startswith('/cgi-bin/token'):
            print(f"[wechat-mock] token path={self.path}", flush=True)
            self._reply(TOKEN_RESP)
        else:
            self._reply(json.dumps({"errcode": 404, "errmsg": f"unknown path {self.path}"}).encode())

    def do_POST(self):
        if not self.path.startswith('/cgi-bin/message/template/send'):
            self._reply(json.dumps({"errcode": 404, "errmsg": f"unknown path {self.path}"}).encode())
            return
        length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(length).decode('utf-8')
        print(f"[wechat-mock] send path={self.path} body={body}", flush=True)
        resp = ERR if '触发失败内容' in body else OK
        self._reply(resp)

    def log_message(self, fmt, *args):
        pass


# 容器化部署时以 MOCK_BIND_HOST=0.0.0.0 覆盖，供 api 容器经服务名访问；本机直跑保持 127.0.0.1。
bind = os.environ.get('MOCK_BIND_HOST', '127.0.0.1')
port = int(os.environ.get('MOCK_PORT', '8090'))
print(f"[wechat-mock] listening on {bind}:{port}", flush=True)
HTTPServer((bind, port), H).serve_forever()