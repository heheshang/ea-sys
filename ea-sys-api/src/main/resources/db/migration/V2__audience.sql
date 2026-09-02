-- M1：接触库与人群圈选
-- 约定：业务表带 tenant_id 行级隔离 + deleted 逻辑删除 + created_at/updated_at
-- audience_snapshot_member 无 tenant_id：其隔离经 snapshot(tenant_id) 间接保证，量大按 snapshot_id 分区（预留）

CREATE TABLE contact (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL REFERENCES tenant (id),
    external_id   VARCHAR(128),
    phone         VARCHAR(32),
    email         VARCHAR(256),
    push_token    VARCHAR(256),
    wechat_openid VARCHAR(128),
    status        VARCHAR(16)  NOT NULL DEFAULT 'active',
    suppression   JSONB        NOT NULL DEFAULT '{}',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted       BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_contact_tenant_external UNIQUE (tenant_id, external_id),
    CONSTRAINT uk_contact_tenant_phone UNIQUE (tenant_id, phone)
);

CREATE TABLE contact_attribute (
    id         BIGSERIAL PRIMARY KEY,
    tenant_id  BIGINT      NOT NULL REFERENCES tenant (id),
    contact_id BIGINT      NOT NULL REFERENCES contact (id),
    key        VARCHAR(64) NOT NULL,
    value      JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_contact_attribute UNIQUE (tenant_id, contact_id, key)
);

CREATE INDEX idx_contact_attribute_contact ON contact_attribute (contact_id);

CREATE TABLE contact_tag (
    id         BIGSERIAL PRIMARY KEY,
    tenant_id  BIGINT      NOT NULL REFERENCES tenant (id),
    contact_id BIGINT      NOT NULL REFERENCES contact (id),
    tag        VARCHAR(64) NOT NULL,
    CONSTRAINT uk_contact_tag UNIQUE (tenant_id, contact_id, tag)
);

CREATE INDEX idx_contact_tag_contact ON contact_tag (contact_id);

CREATE TABLE audience (
    id         BIGSERIAL PRIMARY KEY,
    tenant_id  BIGINT       NOT NULL REFERENCES tenant (id),
    name       VARCHAR(128) NOT NULL,
    rule       JSONB        NOT NULL,
    version    INT          NOT NULL DEFAULT 1,
    status     VARCHAR(16)  NOT NULL DEFAULT 'draft',
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted    BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE TABLE audience_snapshot (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      BIGINT       NOT NULL REFERENCES tenant (id),
    audience_id    BIGINT       NOT NULL REFERENCES audience (id),
    executed_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    member_count   INT          NOT NULL DEFAULT 0,
    status         VARCHAR(16)  NOT NULL DEFAULT 'building',
    filter_version INT          NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted        BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_audience_snapshot_audience ON audience_snapshot (audience_id, executed_at DESC);

CREATE TABLE audience_snapshot_member (
    snapshot_id BIGINT NOT NULL REFERENCES audience_snapshot (id),
    contact_id  BIGINT NOT NULL REFERENCES contact (id),
    PRIMARY KEY (snapshot_id, contact_id)
);

COMMENT ON TABLE contact IS '接触对象（用户画像）';
COMMENT ON TABLE contact_attribute IS '接触对象属性（含智能体分层标签 layer/churn_risk）';
COMMENT ON TABLE contact_tag IS '接触对象标签';
COMMENT ON TABLE audience IS '人群（圈选规则）';
COMMENT ON TABLE audience_snapshot IS '圈选快照（冻结结果）';
COMMENT ON TABLE audience_snapshot_member IS '快照成员（无 tenant_id，隔离随 snapshot）';