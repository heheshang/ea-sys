-- 通道凭据配置：真实供应商适配器（M3 扩展）按租户加密存储。
-- 密文由应用层 AES（easysys.channel.encrypt-password）加密后 Base64 落库，此处仅存密文。
CREATE TABLE channel_config (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT      NOT NULL REFERENCES tenant (id),
    channel          VARCHAR(16) NOT NULL,
    config_encrypted TEXT        NOT NULL,
    enabled          BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted          BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_channel_config_tenant_channel UNIQUE (tenant_id, channel)
);

COMMENT ON TABLE channel_config IS '通道凭据配置（AES 加密，按租户隔离）；适配器发送前经 ChannelConfigProvider 按租户解密注入';
COMMENT ON COLUMN channel_config.channel IS '通道标识：sms / email';
COMMENT ON COLUMN channel_config.config_encrypted IS '配置 JSON（含密钥/口令）AES 加密后 Base64';
COMMENT ON COLUMN channel_config.enabled IS 'FALSE 时适配器视为未配置，降级 console 日志';