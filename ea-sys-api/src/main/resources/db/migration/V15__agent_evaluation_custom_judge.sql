-- M8.5：评测中心对齐（功能说明文档口径）
-- evaluation_report 扩展：LLM 判分轮次（多次取均值）+ 运行追踪 ID（驾驶舱 LLM 调用联动）
-- evaluation_custom_evaluator：用户自定义评测器（LLM-Judge 提示词可配；规则评测器为 Java 参数化规则，不引 Python）

ALTER TABLE evaluation_report
    ADD COLUMN judge_rounds INT NOT NULL DEFAULT 1,       -- LLM 判分轮次（1-5，多次取均值）
    ADD COLUMN trace_id     VARCHAR(96);                  -- 本次运行追踪 ID（eval-xxxx，驾驶舱 LLM 调用按此联动）

COMMENT ON COLUMN evaluation_report.judge_rounds IS 'LLM 判分轮次：1-5，多次判分取均值（仅 llm_judge 类评测器参与）';
COMMENT ON COLUMN evaluation_report.trace_id IS '运行追踪 ID（eval-8 位随机），驾驶舱 LLM 调用追踪按此过滤联动';

CREATE TABLE evaluation_custom_evaluator (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT       NOT NULL REFERENCES tenant (id), -- 租户自定义（互不可见）
    name         VARCHAR(64)  NOT NULL,                   -- 评测器名称
    category     VARCHAR(16)  NOT NULL,                   -- rule：规则评测器 / llm_judge：LLM-Judge
    description  VARCHAR(255),
    rule_type    VARCHAR(32),                             -- 规则评测器类型：keyword_contains / regex_match / length_between
    params       JSONB,                                   -- 规则参数：{keywords, all, prohibit} / {pattern} / {min, max}
    judge_prompt TEXT,                                    -- LLM-Judge 提示词模板（含 {question}/{response}/{reference} 占位）
    status       VARCHAR(8)   NOT NULL DEFAULT 'ENABLED', -- ENABLED / DISABLED
    created_by   VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted      BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_eval_custom_evaluator_tenant ON evaluation_custom_evaluator (tenant_id, category, deleted);

COMMENT ON TABLE evaluation_custom_evaluator IS '自定义评测器：LLM-Judge（judge_prompt 可配提示词）与规则评测器（Java 参数化规则，确定性可测）';
COMMENT ON COLUMN evaluation_custom_evaluator.category IS '类别：rule 规则评测器 / llm_judge LLM-Judge';
COMMENT ON COLUMN evaluation_custom_evaluator.rule_type IS '规则类型：keyword_contains 关键词包含 / regex_match 正则匹配 / length_between 长度区间（仅 rule 类）';
COMMENT ON COLUMN evaluation_custom_evaluator.params IS '规则参数 JSON：keyword_contains {keywords[], all, prohibit} / regex_match {pattern} / length_between {min, max}';
COMMENT ON COLUMN evaluation_custom_evaluator.judge_prompt IS 'LLM-Judge 提示词模板：占位 {question} 用户提问 / {response} 被测响应 / {reference} 参考答案，模型输出 0-100 数字';
COMMENT ON COLUMN evaluation_custom_evaluator.status IS '状态：ENABLED 启用 / DISABLED 停用';