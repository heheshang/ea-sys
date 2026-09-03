#!/usr/bin/env python3
"""开发/端到端验证用的 SMTP 供应商 mock 服务器（本地投递，不发真实邮件）。

配合 dev 库租户 1 的 channel_config(email) 使用：smtpHost=127.0.0.1、smtpPort=3025，
app 触发真实发送后本进程打印信封（from/to）与邮件首部。

启动：python3 scripts/e2e_smtp_mock.py
注意：Python 3.12 移除 smtpd 模块，需用 Python 3.11 或以下运行本脚本。
验证：执行工作流后观察输出；delivery_record 应出现 smtp-* DELIVERED。
"""
import os
import smtpd
import asyncore

class S(smtpd.SMTPServer):
    def process_message(self, peer, mailfrom, rcpttos, data, **kwargs):
        print(f"[smtp-mock] from={mailfrom} to={rcpttos}", flush=True)
        head = data if isinstance(data, str) else data.decode('utf-8', 'replace')
        # 打印信封与首部，正文截断
        print(f"[smtp-mock] data-head={head[:400]!r}", flush=True)

# 容器化部署时以 MOCK_BIND_HOST=0.0.0.0 覆盖，供 api 容器经服务名访问；本机直跑保持 127.0.0.1。
bind = os.environ.get('MOCK_BIND_HOST', '127.0.0.1')
port = int(os.environ.get('MOCK_PORT', '3025'))
print(f"[smtp-mock] listening on {bind}:{port}", flush=True)
S((bind, port), None)
asyncore.loop()