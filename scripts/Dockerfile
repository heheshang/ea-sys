# e2e mock 服务镜像：三个通道供应商 mock 共用一个 python:3.11-slim 镜像。
# 注意：smtp mock 依赖 Python 3.12 已移除的 smtpd 模块，必须用 3.11。
FROM python:3.11-slim
WORKDIR /app
COPY e2e_smtp_mock.py e2e_wechat_mock.py e2e_sms_mock.py ./