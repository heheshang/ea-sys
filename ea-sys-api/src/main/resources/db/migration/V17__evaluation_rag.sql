-- V17 增强：评测中心 RAG 评测（rag_hit_rate：execute 专属知识库检索命中率）
-- evaluation_case.expected_kb_hits：期望知识库命中要点字符串数组（rag_hit_rate 判分基准），
-- 判分侧比对 execute 模式实际 search_kb 工具结果的命中文档拼接文本（actual_tool_results）

ALTER TABLE evaluation_case
    ADD COLUMN expected_kb_hits JSONB; -- 期望知识库命中要点 JSONB（字符串数组，可选；openjudge/非 execute 用例可空）

COMMENT ON COLUMN evaluation_case.expected_kb_hits IS '期望知识库命中要点 JSONB（字符串数组）：rag_hit_rate 判分基准，execute 专属，可选（如 ["会员权益包括","积分翻倍"]）';