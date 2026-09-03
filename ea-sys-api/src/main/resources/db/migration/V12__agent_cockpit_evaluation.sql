-- M8：驾驶舱（图谱管理 + 监控总览）与评测中心
-- agent_graph_entry：知识领域登记 + 状态管理（模块判别列，无图数据库；内置目录与用户行按 entry_key 合并）
-- evaluation_dataset / evaluation_case / evaluation_report：数据集 + 用例 + 评测报告（评测器为代码内置目录，不落表）

CREATE TABLE agent_graph_entry (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT       NOT NULL REFERENCES tenant (id),
    module       VARCHAR(16)  NOT NULL,                -- ONTOLOGY / SKILL / TOOL / MCP / SUBAGENT / MEMORY / KNOWLEDGE / EVALUATION
    entry_key    VARCHAR(64)  NOT NULL,                -- 模块内唯一标识（内置目录同 key 用户行覆盖内置）
    name         VARCHAR(128) NOT NULL,
    description  TEXT,
    payload      JSONB,                                -- 模块元数据（能力/参数/来源等）
    status       VARCHAR(8)   NOT NULL DEFAULT 'ENABLED', -- ENABLED / DISABLED
    version      VARCHAR(32),
    created_by   VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted      BOOLEAN      NOT NULL DEFAULT FALSE,
    UNIQUE (tenant_id, module, entry_key)
);

CREATE INDEX idx_agent_graph_entry_tenant_module ON agent_graph_entry (tenant_id, module, deleted);

COMMENT ON TABLE agent_graph_entry IS '驾驶舱图谱登记：八类知识领域（本体/技能/工具/MCP/子Agent/记忆/知识库/评测中心）清单与状态';
COMMENT ON COLUMN agent_graph_entry.module IS '知识领域：ONTOLOGY 本体 / SKILL 技能 / TOOL 工具 / MCP MCP / SUBAGENT 子Agent / MEMORY 记忆 / KNOWLEDGE 知识库 / EVALUATION 评测中心';
COMMENT ON COLUMN agent_graph_entry.payload IS '模块元数据 JSON（能力描述/参数 schema/来源等）';
COMMENT ON COLUMN agent_graph_entry.status IS '状态：ENABLED 启用 / DISABLED 停用';

CREATE TABLE evaluation_dataset (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT       NOT NULL REFERENCES tenant (id),
    name         VARCHAR(128) NOT NULL,
    description  TEXT,
    scope        VARCHAR(16)  NOT NULL DEFAULT 'llm_call', -- llm_call：LLM 调用链路（提示词/工具/响应）
    mode         VARCHAR(16)  NOT NULL DEFAULT 'openjudge', -- openjudge：预置响应直接判分 / execute：先运行被测智能体取实际输出
    status       VARCHAR(8)   NOT NULL DEFAULT 'ENABLED',   -- ENABLED / DISABLED
    created_by   VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted      BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_evaluation_dataset_tenant ON evaluation_dataset (tenant_id, deleted);

COMMENT ON TABLE evaluation_dataset IS '评测中心数据集：LLM 调用链路评测用例集合（提示词/工具/期望答案）';
COMMENT ON COLUMN evaluation_dataset.scope IS '评测范围：llm_call（LLM 调用链路）';
COMMENT ON COLUMN evaluation_dataset.mode IS '运行模式：openjudge 预置响应直接判分 / execute 先执行被测智能体';
COMMENT ON COLUMN evaluation_dataset.status IS '状态：ENABLED 启用 / DISABLED 停用';

CREATE TABLE evaluation_case (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT       NOT NULL REFERENCES tenant (id),
    dataset_id       BIGINT       NOT NULL REFERENCES evaluation_dataset (id),
    seq              INT          NOT NULL DEFAULT 0,  -- 用例序号（数据集内排序）
    question         TEXT         NOT NULL,            -- 用户提示词
    system_prompt    TEXT,                              -- 系统提示词（可选）
    expected_output  JSONB,                             -- 期望答案（判分基准，字符串/数字/对象均可）
    tool_schema      JSONB,                             -- 工具定义（可选）
    expected_tool    JSONB,                             -- 期望工具调用 {name, args}
    provided_response TEXT,                             -- OpenJudge 预置模型响应（跳过执行直接判分）
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted          BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_evaluation_case_dataset ON evaluation_case (dataset_id, seq, deleted);

COMMENT ON TABLE evaluation_case IS '评测用例：用户提示词 + 期望答案 + 可选工具/系统提示词/预置响应';
COMMENT ON COLUMN evaluation_case.expected_output IS '期望答案 JSON（number_accuracy/string_exact/text_similarity 等判分基准）';
COMMENT ON COLUMN evaluation_case.expected_tool IS '期望工具调用 JSON {name, args}（工具调用正确性判分基准）';
COMMENT ON COLUMN evaluation_case.provided_response IS 'OpenJudge 预置响应文本：openjudge 模式直接作为实际响应判分，跳过被测智能体执行';

CREATE TABLE evaluation_report (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT       NOT NULL REFERENCES tenant (id),
    dataset_id   BIGINT       NOT NULL REFERENCES evaluation_dataset (id),
    name         VARCHAR(128) NOT NULL,
    total_cases  INT          NOT NULL DEFAULT 0,      -- 数据集用例总数
    tested_cases INT          NOT NULL DEFAULT 0,      -- 实际判分用例数
    metrics      JSONB,                                -- 指标均值 [{metric, category, avg_score, passed_count}]
    findings     JSONB,                                -- 分级发现 [{level, dimension, detail, suggestion}]
    summary      JSONB,                                -- 汇总 {score, verdict}
    confidence   NUMERIC(4,3),
    model        VARCHAR(64),                          -- 判分模型位（deterministic / 模型 id）
    mode         VARCHAR(16),
    created_by   VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted      BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_evaluation_report_tenant ON evaluation_report (tenant_id, created_at DESC);

COMMENT ON TABLE evaluation_report IS '评测报告：批量运行评测后的指标均值 + 分级发现 + 汇总 verdict，回看与对比基线';
COMMENT ON COLUMN evaluation_report.metrics IS '指标均值 JSON：[{metric, category(rule/llm_judge), avg_score, passed_count, applicable_count}]';
COMMENT ON COLUMN evaluation_report.findings IS '分级发现 JSON：[{level(INFO/WARNING/BLOCKED), dimension, detail, suggestion}]';
COMMENT ON COLUMN evaluation_report.summary IS '汇总 JSON：{score 0-100, verdict PASS/WARN/FAIL}';
COMMENT ON COLUMN evaluation_report.confidence IS '报告置信度 0-1（确定性规则模式恒 1.0）';