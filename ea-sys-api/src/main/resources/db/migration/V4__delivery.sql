-- M3：触达执行
-- template：租户级触达模板（FreeMarker 渲染，channel 决定适配器）
-- delivery_record：每次真实下发一行；唯一键 (tenant_id, contact_id, execution_id, node_key) 幂等防重
-- 治理（退订/频率）不落 delivery_record（未发送），在节点 output.skipped 中统计

CREATE TABLE template (
    id         BIGSERIAL PRIMARY KEY,
    tenant_id  BIGINT       NOT NULL REFERENCES tenant (id),
    channel    VARCHAR(16)  NOT NULL,
    name       VARCHAR(128) NOT NULL,
    content    TEXT         NOT NULL,
    status     VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted    BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_template_tenant_channel ON template (tenant_id, channel);

CREATE TABLE delivery_record (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT      NOT NULL REFERENCES tenant (id),
    contact_id      BIGINT      NOT NULL REFERENCES contact (id),
    execution_id    BIGINT      NOT NULL REFERENCES execution (id),
    node_key        VARCHAR(64) NOT NULL,
    channel         VARCHAR(16) NOT NULL,
    template_id     BIGINT      REFERENCES template (id),
    content         TEXT,
    channel_msg_id  VARCHAR(128),
    status          VARCHAR(16) NOT NULL,
    error           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_delivery_execution_node_contact UNIQUE (tenant_id, contact_id, execution_id, node_key)
);

CREATE INDEX idx_delivery_execution ON delivery_record (execution_id);
CREATE INDEX idx_delivery_contact ON delivery_record (tenant_id, contact_id, created_at DESC);

COMMENT ON TABLE template IS '触达模板（FreeMarker 渲染）';
COMMENT ON TABLE delivery_record IS '下发记录（唯一键幂等防重，回执状态回流）';