-- M8 增强：评测中心 execute 真实执行链路（参考 τ-bench / GAIA / SWE-bench 的执行维度指标）
-- evaluation_dataset.agent_type：execute 模式被测智能体（assistant / workflow-dialogue）
-- evaluation_case.expected_steps / expected_policy：执行维度评测器（step_efficiency / policy_compliance）判分基准

ALTER TABLE evaluation_dataset
    ADD COLUMN agent_type VARCHAR(32) NOT NULL DEFAULT 'assistant'; -- 被测智能体：assistant / workflow-dialogue（execute 模式适用，openjudge 忽略）

COMMENT ON COLUMN evaluation_dataset.agent_type IS '被测智能体：assistant 运营助手 / workflow-dialogue 工作流对话（execute 模式逐用例真实执行；openjudge 预置响应模式忽略）';

ALTER TABLE evaluation_case
    ADD COLUMN expected_steps INT NOT NULL DEFAULT 1, -- 期望执行步数（step_efficiency 基准：期望工具调用数+1 或纯响应步数）
    ADD COLUMN expected_policy JSONB;                 -- 期望策略条款 [{keyword, prohibit}] 等（policy_compliance 违规检测基准）

COMMENT ON COLUMN evaluation_case.expected_steps IS '期望执行步数：step_efficiency = min(1, 期望步数/实际步数)，缺省 1';
COMMENT ON COLUMN evaluation_case.expected_policy IS '期望策略条款 JSONB：policy_compliance 违规关键词/短语检测基准（如 [{"keyword":"确认后下发","prohibit":false}]）';