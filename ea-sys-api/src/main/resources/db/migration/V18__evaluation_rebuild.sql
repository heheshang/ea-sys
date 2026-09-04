-- V18：评测中心重构 P0/P1 —— 用例分层(category) + 逐用例判分(judge_rule) + 多轮对话(dialogue)
--       + 数据集版本快照(evaluation_dataset_version) + 报告版本溯源(dataset_version_id/no)
-- P3 预留（本轮只建表/建列，不写值）：evaluation_transcript / evaluation_human_review /
--       report.env_snapshot / code_snapshot / execution / layering

-- 1) 用例分层与逐用例判分/对话
ALTER TABLE evaluation_case
    ADD COLUMN category VARCHAR(16) NOT NULL DEFAULT 'basic',
    ADD COLUMN judge_rule JSONB,
    ADD COLUMN dialogue JSONB;

ALTER TABLE evaluation_case
    ADD CONSTRAINT ck_evaluation_case_category CHECK (category IN ('basic', 'edge', 'real'));

COMMENT ON COLUMN evaluation_case.category IS '用例分层：basic 基础场景 / edge 边界场景 / real 真实轨迹（目标分布 40/30/30，运行时分档校验）';
COMMENT ON COLUMN evaluation_case.judge_rule IS '逐用例判分规则 JSONB（覆盖评测器默认判定，可选）';
COMMENT ON COLUMN evaluation_case.dialogue IS '多轮对话用例 JSONB（LLM 调用链路多轮评测输入，可选）';

-- 2) 数据集版本快照：工作区用例快照，发布即不可变；run/task 绑定版本读取
CREATE TABLE evaluation_dataset_version (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT       NOT NULL REFERENCES tenant (id),
    dataset_id   BIGINT       NOT NULL REFERENCES evaluation_dataset (id),
    version_no   INT          NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'PUBLISHED',
    cases        JSONB        NOT NULL,
    evaluators   JSONB,
    published_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by   VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted      BOOLEAN      NOT NULL DEFAULT FALSE,
    UNIQUE (tenant_id, dataset_id, version_no)
);

CREATE INDEX idx_eval_version_dataset ON evaluation_dataset_version (dataset_id, deleted, version_no DESC);

COMMENT ON TABLE evaluation_dataset_version IS '评测数据集版本：发布时刻工作区用例快照（全字段同构、按 seq 升序），run/task 绑定版本读取，支持回看与对比';
COMMENT ON COLUMN evaluation_dataset_version.version_no IS '数据集内版本号（单调递增，删除不复用，保 UNIQUE 无碰撞）';
COMMENT ON COLUMN evaluation_dataset_version.status IS '版本状态：PUBLISHED 已发布（本轮仅此值，DRAFT 留后续）';
COMMENT ON COLUMN evaluation_dataset_version.cases IS '用例快照 JSON 数组（每用例含 seq/question/system_prompt/category/judge_rule/dialogue/expected_output/tool_schema/expected_tool/expected_steps/expected_policy/expected_kb_hits/provided_response）';
COMMENT ON COLUMN evaluation_dataset_version.evaluators IS '发布时刻评测器选择快照 JSON（本轮发布不固化评测器，null）';
COMMENT ON COLUMN evaluation_dataset_version.published_at IS '发布时间（快照生效时刻）';
COMMENT ON COLUMN evaluation_dataset_version.created_by IS '发布操作人（与既有表 created_by VARCHAR 惯例一致）';

-- 3) 报告版本溯源 + P3 预留列
ALTER TABLE evaluation_report
    ADD COLUMN dataset_version_id BIGINT,
    ADD COLUMN dataset_version_no INT,
    ADD COLUMN env_snapshot JSONB,
    ADD COLUMN code_snapshot JSONB,
    ADD COLUMN execution JSONB,
    ADD COLUMN layering JSONB;

COMMENT ON COLUMN evaluation_report.dataset_version_id IS '运行绑定数据集版本 id（缺省取最新已发布版本；无版本回退实时用例则 null）';
COMMENT ON COLUMN evaluation_report.dataset_version_no IS '运行绑定数据集版本号（与 dataset_version_id 同源）';
COMMENT ON COLUMN evaluation_report.env_snapshot IS 'P3：运行环境快照 JSON（依赖/参数/评测器目录）';
COMMENT ON COLUMN evaluation_report.code_snapshot IS 'P3：代码/配置快照 JSON（版本控制标识）';
COMMENT ON COLUMN evaluation_report.execution IS 'P3：执行轨迹详情 JSON（逐用例执行/判分明细）';
COMMENT ON COLUMN evaluation_report.layering IS 'P3：分层分布校验结果 JSON（参与用例三档计数与判定）';

-- 4) P3：评测运行转录（只建表）
CREATE TABLE evaluation_transcript (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT       NOT NULL REFERENCES tenant (id),
    report_id    BIGINT       NOT NULL REFERENCES evaluation_report (id),
    case_seq     INT          NOT NULL,
    turn_no      INT          NOT NULL DEFAULT 0,
    role         VARCHAR(16)  NOT NULL,
    text         TEXT,
    thinking     TEXT,
    tool_use     JSONB,
    tool_result  JSONB,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_eval_transcript_report ON evaluation_transcript (report_id, case_seq, turn_no);

COMMENT ON TABLE evaluation_transcript IS 'P3：评测运行转录（逐用例逐轮消息，可回放与审计）';
COMMENT ON COLUMN evaluation_transcript.role IS '消息角色：USER 用户 / ASSISTANT 智能体 / TOOL 工具结果';
COMMENT ON COLUMN evaluation_transcript.tool_use IS '工具调用 JSON（ASSISTANT 轮）';
COMMENT ON COLUMN evaluation_transcript.tool_result IS '工具结果 JSON（TOOL 轮）';

-- 5) P3：人工评审（只建表）
CREATE TABLE evaluation_human_review (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT       NOT NULL REFERENCES tenant (id),
    report_id    BIGINT       NOT NULL REFERENCES evaluation_report (id),
    case_seq     INT          NOT NULL,
    metric       VARCHAR(64)  NOT NULL,
    score        NUMERIC(3,2),
    verdict      VARCHAR(16),
    note         TEXT,
    reviewer     VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted      BOOLEAN      NOT NULL DEFAULT FALSE,
    UNIQUE (report_id, case_seq, metric, deleted)
);

CREATE INDEX idx_eval_human_review_report ON evaluation_human_review (report_id, case_seq);

COMMENT ON TABLE evaluation_human_review IS 'P3：人工评审（逐用例逐指标人工复核，与自动判分对齐）';
COMMENT ON COLUMN evaluation_human_review.score IS '人工评分 0-1（两位小数，缺省 null 表示未评分）';
COMMENT ON COLUMN evaluation_human_review.verdict IS '人工判定：PASS 通过 / FAIL 不通过 / SKIP 跳过';

-- ===BACKFILL===

-- 迁移前活跃数据集（deleted=false）生成 v1 快照：用例全字段 jsonb 按 seq 排序；
-- 无用例数据集（LEFT JOIN 行的 c.id 为 NULL 被 FILTER 剔除）→ '[]'；
INSERT INTO evaluation_dataset_version (tenant_id, dataset_id, version_no, status, cases, evaluators, published_at, created_by, created_at)
SELECT d.tenant_id, d.id, 1, 'PUBLISHED',
       COALESCE(jsonb_agg(jsonb_build_object(
           'seq', c.seq,
           'question', c.question,
           'system_prompt', c.system_prompt,
           'category', c.category,
           'judge_rule', c.judge_rule,
           'dialogue', c.dialogue,
           'expected_output', c.expected_output,
           'tool_schema', c.tool_schema,
           'expected_tool', c.expected_tool,
           'expected_steps', c.expected_steps,
           'expected_policy', c.expected_policy,
           'expected_kb_hits', c.expected_kb_hits,
           'provided_response', c.provided_response)
           ORDER BY c.seq) FILTER (WHERE c.id IS NOT NULL),
           '[]'::jsonb),
       NULL, now(), NULL, now()
FROM evaluation_dataset d
LEFT JOIN evaluation_case c ON c.dataset_id = d.id AND c.deleted = FALSE
WHERE d.deleted = FALSE
GROUP BY d.tenant_id, d.id;

-- 历史报告回填：绑定其数据集 v1 快照（dataset_version_id/no 溯源）
UPDATE evaluation_report r
SET dataset_version_id = v.id, dataset_version_no = v.version_no
FROM evaluation_dataset_version v
WHERE v.dataset_id = r.dataset_id AND v.version_no = 1
  AND r.deleted = FALSE AND r.dataset_version_id IS NULL;