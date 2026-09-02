-- M2：DAG 工作流引擎（画布 + 版本化 + 执行/干跑）
-- 版本化语义：save 时 DRAFT 覆盖当前版本行；PUBLISHED 后 save 生成 version+1 新行，
-- 旧发布版本行保留（execution.workflow_version 回读，进行中执行不受新发布影响）
-- ref_id：业务 id，同一工作流所有版本行共享（对外 URL / 引用一律用 ref_id；id 仅物理行主键）

CREATE TABLE workflow (
    id           BIGSERIAL PRIMARY KEY,
    ref_id       BIGINT       NOT NULL DEFAULT 0,   -- 应用在首次插入后回填为真实业务 id
    tenant_id    BIGINT       NOT NULL REFERENCES tenant (id),
    name         VARCHAR(128) NOT NULL,
    description  TEXT,
    status       VARCHAR(16)  NOT NULL DEFAULT 'draft',  -- draft / published / archived
    version      INT          NOT NULL DEFAULT 1,        -- 当前画布数据版本
    created_by   VARCHAR(64),
    published_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted      BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_workflow_ref ON workflow (ref_id, status);

CREATE TABLE workflow_node (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL REFERENCES tenant (id),
    workflow_id BIGINT       NOT NULL,                   -- 业务 id（ref_id），非版本行主键
    version     INT          NOT NULL,
    node_key    VARCHAR(64)  NOT NULL,
    type        VARCHAR(16)  NOT NULL,  -- TRIGGER / CONDITION / AGENT_SPLIT / DELAY / ACTION / UPDATE / END
    name        VARCHAR(128) NOT NULL,
    config      JSONB        NOT NULL DEFAULT '{}',
    position    JSONB        NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_workflow_node_key UNIQUE (workflow_id, version, node_key)
);

CREATE INDEX idx_workflow_node_workflow ON workflow_node (workflow_id, version);

CREATE TABLE workflow_edge (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL REFERENCES tenant (id),
    workflow_id BIGINT       NOT NULL,                   -- 业务 id（ref_id）
    version     INT          NOT NULL,
    source_key  VARCHAR(64)  NOT NULL,
    target_key  VARCHAR(64)  NOT NULL,
    condition   JSONB,                     -- CONDITION 出边判定 DSL；无条件边 = 兜底分支
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_workflow_edge UNIQUE (workflow_id, version, source_key, target_key)
);

CREATE INDEX idx_workflow_edge_workflow ON workflow_edge (workflow_id, version);

CREATE TABLE execution (
    id                   BIGSERIAL PRIMARY KEY,
    tenant_id            BIGINT       NOT NULL REFERENCES tenant (id),
    workflow_id          BIGINT       NOT NULL,          -- 业务 id（ref_id）
    workflow_version     INT          NOT NULL,           -- 执行时画布版本（版本隔离）
    trigger_type         VARCHAR(16)  NOT NULL,           -- SCHEDULED / EVENT / MANUAL / API
    trigger_payload      JSONB        NOT NULL DEFAULT '{}',
    audience_snapshot_id BIGINT       REFERENCES audience_snapshot (id),  -- 批量触发的人群快照
    dry_run              BOOLEAN      NOT NULL DEFAULT FALSE,             -- 干跑：模拟执行不真实触达
    status               VARCHAR(16)  NOT NULL DEFAULT 'running',  -- running/succeeded/failed/partial/canceled
    started_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at          TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted              BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_execution_workflow ON execution (workflow_id, id DESC);

CREATE TABLE execution_node_state (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT       NOT NULL REFERENCES tenant (id),
    execution_id BIGINT       NOT NULL REFERENCES execution (id),
    node_key     VARCHAR(64)  NOT NULL,
    node_type    VARCHAR(16)  NOT NULL,
    node_name    VARCHAR(128) NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'pending',  -- pending/running/done/failed/skipped
    attempt      INT          NOT NULL DEFAULT 0,
    in_at        TIMESTAMPTZ,
    out_at       TIMESTAMPTZ,
    output       JSONB        NOT NULL DEFAULT '{}',       -- 节点输出（干跑：预计触达数/分流结果等）
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted      BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_execution_node UNIQUE (execution_id, node_key)
);

CREATE INDEX idx_execution_node_state_exec ON execution_node_state (execution_id);

COMMENT ON TABLE workflow IS '工作流（画布容器，status+version 指针；ref_id 为业务 id）';
COMMENT ON TABLE workflow_node IS '画布节点（按 version 版本化，多版本行共存）';
COMMENT ON TABLE workflow_edge IS '画布边（出边条件：CONDITION 多出口判定）';
COMMENT ON TABLE execution IS '执行实例（dry_run=true 为干跑，报告聚合自 node_state）';
COMMENT ON TABLE execution_node_state IS '节点执行状态（干跑报告数据源，冗余 type/name 自包含）';