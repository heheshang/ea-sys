package com.easysys.api.dto.cockpit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 驾驶舱监控总览：LLM 调用聚合 + 图谱状态 + 知识库/记忆 + Agent 目录。
 */
public record CockpitOverviewView(
        LlmOverview llm,
        GraphOverview graph,
        KnowledgeOverview knowledge,
        MemoryOverview memory,
        AgentsOverview agents) {

    /** LLM 调用聚合（近 7 天 audit_log + llm_usage）。 */
    public record LlmOverview(
            boolean enabled,
            String modelId,
            long calls,
            long success,
            long fallback,
            long error,
            double avgDurationMs,
            long sumTokens,
            BigDecimal sumCost,
            double schemaValidRate,
            double errorRate,
            double fallbackRate,
            long rounds,
            long sumInputTokens,
            long sumOutputTokens,
            long sumCachedTokens,
            LlmContext context,
            List<LlmSeries> byAgent,
            List<LlmSeries> byModel,
            List<LlmTrend> trend) {
    }

    /** 近 7 天最近一次对话 LLM 调用的输入构成快照（null = LLM 未启用或暂无对话调用）。 */
    public record LlmContext(
            int entries,
            long tokens,
            List<LlmContextCategory> categories) {
    }

    /** 单类上下文统计（占比前端计算）。key 枚举：system/tool_schema/user/assistant/injected/tool_result。 */
    public record LlmContextCategory(
            String key,
            int entries,
            long tokens) {
    }

    /** 按 agent_type / model 分组的聚合行。 */
    public record LlmSeries(
            String name,
            long calls,
            long success,
            long fallback,
            long error,
            double avgDurationMs,
            long sumTokens,
            BigDecimal sumCost) {
    }

    /** 按天聚合（trend）。 */
    public record LlmTrend(
            String day,
            long calls,
            long success,
            long sumTokens,
            BigDecimal sumCost) {
    }

    /** 图谱状态（内置目录 ∪ 用户登记，按模块统计）。 */
    public record GraphOverview(
            long total,
            long enabled,
            List<ModuleStat> modules) {
    }

    /** 单模块图谱统计。 */
    public record ModuleStat(
            String module,
            long total,
            long enabled) {
    }

    /** 知识库统计。 */
    public record KnowledgeOverview(
            long docs,
            long chunks) {
    }

    /** 记忆/会话状态统计（Redis agentscope 键）。 */
    public record MemoryOverview(
            long keys) {
    }

    /** Agent 目录与 LLM 状态。 */
    public record AgentsOverview(
            List<AgentStat> byType) {
    }

    /** 单 Agent 类型登记状态。 */
    public record AgentStat(
            String type,
            String name,
            boolean llmEnabled,
            String modelId) {
    }
}