-- M0 基线：租户与系统用户
CREATE TABLE tenant (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(128) NOT NULL,
    status     VARCHAR(16)  NOT NULL DEFAULT 'active',
    settings   JSONB        NOT NULL DEFAULT '{}',
    quota      JSONB        NOT NULL DEFAULT '{}',
    timezone   VARCHAR(64)  NOT NULL DEFAULT 'Asia/Shanghai',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted    BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE TABLE sys_user (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL REFERENCES tenant (id),
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(32)  NOT NULL DEFAULT 'operator',
    status        VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted       BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_sys_user_tenant_username UNIQUE (tenant_id, username)
);

COMMENT ON TABLE tenant IS '租户';
COMMENT ON TABLE sys_user IS '系统用户';