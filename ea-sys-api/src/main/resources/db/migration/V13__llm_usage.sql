-- M9：LLM 用量明细（LLM 卡：总 Token/提问轮次/LLM 调用/输入/输出/缓存命中 + 上下文构成）
-- llm_usage：中间件采集，每 (tenant, agent_type, session) 一行 upsert 累计。
--   calls/rounds 计数累加；input/output/cached 累加；context 覆盖为最近一次模型调用输入构成快照。
-- 确定性模式（LLM 未启用）不发真实 usage，中间件不写行 —— 表保持空，驾驶舱统一显示「—」。

CREATE TABLE llm_usage (
    id            BIGSERIAL   PRIMARY KEY,
    tenant_id     BIGINT      NOT NULL REFERENCES tenant (id),
    agent_type    VARCHAR(32) NOT NULL,             -- assistant / workflow-dialogue / layer-strategy / cockpit-insights ...
    session_id    VARCHAR(128) NOT NULL,            -- 聊天会话 Id / 批处理 action
    calls         INT         NOT NULL DEFAULT 0,   -- LLM 模型调用次数（含 ReAct 工具循环内多次调用）
    rounds        INT         NOT NULL DEFAULT 0,   -- 聊天提问轮次（ai-chat 请求次数；批处理不算）
    input_tokens  BIGINT      NOT NULL DEFAULT 0,
    output_tokens BIGINT      NOT NULL DEFAULT 0,
    cached_tokens BIGINT      NOT NULL DEFAULT 0,   -- 缓存命中 ∈ 输入
    context       JSONB,                            -- 最近一次调用的输入构成快照 {entries, tokens, categories:[{key,entries,tokens}]}
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, agent_type, session_id)
);

CREATE INDEX idx_llm_usage_tenant_updated ON llm_usage (tenant_id, updated_at DESC);

COMMENT ON TABLE llm_usage IS 'LLM 用量明细：会话级累计（中间件采集，仅真实 LLM 调用写行）';
COMMENT ON COLUMN llm_usage.calls IS 'LLM 模型调用次数（每次 onModelCall +1，含工具循环重入）';
COMMENT ON COLUMN llm_usage.rounds IS '聊天提问轮次（ai-chat 请求入口 +1；批处理不记轮次）';
COMMENT ON COLUMN llm_usage.context IS '最近一次模型调用输入构成：{entries 条目数, tokens 估算总 Token, categories 六类明细}';
COMMENT ON COLUMN llm_usage.cached_tokens IS '缓存命中 Token（是输入的子集）';