package com.easysys.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 智能体决策提供方（分层/路由）。LLM 实现是后续增量层（qwen-max/turbo），
 * 本里程碑交付确定性实现；无论何种提供方，产出都必须满足 {@link #schema()}，
 * 框架层校验失败或低置信即落入确定性 fallback（见 {@link AgentPolicy}）。
 * 实现须无状态、可重试调用（幂等）。
 */
public interface StrategyAgent {

    /** 智能体类型（LAYER / ROUTER），写入审计。 */
    AgentType type();

    /** 输出 JSON Schema（Draft-07）；空串 = 不做结构校验。 */
    String schema();

    /** 执行一次决策。异常由 AgentPolicy 捕获并走 fallback。 */
    JsonNode plan(JsonNode input) throws Exception;
}