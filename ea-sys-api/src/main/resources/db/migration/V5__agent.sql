-- M4：智能体接入（分层/路由 Agent：schema 校验 + 确定性降级）
-- layer_strategy：租户分层策略文档（当前为确定性规则生成，LLM 接入后走同一 schema 校验与发布闸门）
-- audit_log：智能体调用审计（入参摘要/输出/schema 校验结果/置信度/模型/耗时；追加写不删除）

CREATE TABLE layer_strategy (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT       NOT NULL REFERENCES tenant (id),
    name             VARCHAR(128) NOT NULL,
    dimensions       JSONB        NOT NULL,
    route_order      JSONB        NOT NULL,
    strategy         JSONB        NOT NULL,
    source           VARCHAR(16)  NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'draft',
    strategy_version VARCHAR(64)  NOT NULL,
    confidence       NUMERIC(4,3) NOT NULL,
    created_by       VARCHAR(64),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at     TIMESTAMPTZ,
    deleted          BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_layer_strategy_tenant_version UNIQUE (tenant_id, strategy_version)
);

CREATE INDEX idx_layer_strategy_tenant_status ON layer_strategy (tenant_id, status);

CREATE TABLE audit_log (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT      NOT NULL REFERENCES tenant (id),
    agent_type       VARCHAR(16) NOT NULL,
    action           VARCHAR(64) NOT NULL,
    status           VARCHAR(16) NOT NULL,
    reason           VARCHAR(128),
    input_summary    JSONB,
    output           JSONB,
    schema_valid     BOOLEAN,
    strategy_version VARCHAR(64),
    confidence       NUMERIC(4,3),
    model            VARCHAR(64),
    tokens           INT,
    duration_ms      BIGINT,
    cost             NUMERIC(12,4),
    operator         VARCHAR(64),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_tenant_time ON audit_log (tenant_id, created_at DESC);

COMMENT ON TABLE layer_strategy IS '租户分层策略（AGENT_SPLIT 分流依据，发布后生效）';
COMMENT ON TABLE audit_log IS '智能体调用审计（schema 校验结果与降级原因）';