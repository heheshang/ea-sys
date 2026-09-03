package com.easysys.api.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.easysys.api.entity.LlmUsage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

/**
 * LLM 用量明细（驾驶舱 LLM 卡）：会话级 upsert 累计 + 近 7 天聚合。
 * 与 AgentAuditMapper 同款租户策略：绕过租户插件，显式 tenant_id 过滤。
 */
public interface LlmUsageMapper extends BaseMapper<LlmUsage> {

    /** 模型调用记账：一行 +1 calls，token 累加，context 覆盖为本次调用构成（真实 LLM usage 才调用）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            INSERT INTO llm_usage (tenant_id, agent_type, session_id, calls, rounds,
                                   input_tokens, output_tokens, cached_tokens, context, created_at, updated_at)
            VALUES (#{tenantId}, #{agentType}, #{sessionId}, 1, 0,
                    #{inputTokens}, #{outputTokens}, #{cachedTokens}, CAST(#{context} AS JSONB), now(), now())
            ON CONFLICT (tenant_id, agent_type, session_id) DO UPDATE SET
                calls = llm_usage.calls + 1,
                input_tokens = llm_usage.input_tokens + EXCLUDED.input_tokens,
                output_tokens = llm_usage.output_tokens + EXCLUDED.output_tokens,
                cached_tokens = llm_usage.cached_tokens + EXCLUDED.cached_tokens,
                context = EXCLUDED.context,
                updated_at = now()
            """)
    int upsertCall(@Param("tenantId") Long tenantId, @Param("agentType") String agentType,
                   @Param("sessionId") String sessionId, @Param("inputTokens") long inputTokens,
                   @Param("outputTokens") long outputTokens, @Param("cachedTokens") long cachedTokens,
                   @Param("context") String context);

    /** 聊天提问轮次：一行 +1 rounds（无行时先建 0 调用占位行）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            INSERT INTO llm_usage (tenant_id, agent_type, session_id, calls, rounds, created_at, updated_at)
            VALUES (#{tenantId}, #{agentType}, #{sessionId}, 0, 1, now(), now())
            ON CONFLICT (tenant_id, agent_type, session_id) DO UPDATE SET
                rounds = llm_usage.rounds + 1,
                updated_at = now()
            """)
    int markRound(@Param("tenantId") Long tenantId, @Param("agentType") String agentType,
                  @Param("sessionId") String sessionId);

    /** 驾驶舱：近 7 天全部通道 LLM 用量聚合（calls 含批处理与聊天，rounds 仅聊天计；token 为权威全量）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT COALESCE(SUM(calls), 0)        AS calls,
                   COALESCE(SUM(rounds), 0)       AS rounds,
                   COALESCE(SUM(calls) FILTER (WHERE agent_type IN ('assistant', 'workflow-dialogue')), 0) AS chat_calls,
                   COALESCE(SUM(input_tokens), 0) AS input_tokens,
                   COALESCE(SUM(output_tokens), 0) AS output_tokens,
                   COALESCE(SUM(cached_tokens), 0) AS cached_tokens
            FROM llm_usage
            WHERE tenant_id = #{tenantId} AND updated_at >= now() - interval '7 days'
            """)
    Map<String, Object> selectAggregate(@Param("tenantId") Long tenantId);

    /** 驾驶舱：近 7 天最近一次对话 LLM 调用的输入构成快照（快照兜底；无调用时为 null）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT context
            FROM llm_usage
            WHERE tenant_id = #{tenantId}
              AND agent_type IN ('assistant', 'workflow-dialogue')
              AND updated_at >= now() - interval '7 days'
              AND context IS NOT NULL
            ORDER BY updated_at DESC, id DESC
            LIMIT 1
            """)
    String selectLastChatContext(@Param("tenantId") Long tenantId);

    /**
     * 驾驶舱：近 7 天最近一次聊天会话 {@code {agent_type, session_id}}。
     * 会话台账含提问轮次行（markRound，LLM 未启用也记），故不带 context IS NOT NULL ——
     * 会话定位后由查询期 AgentState 实时转录派生构成。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT agent_type, session_id
            FROM llm_usage
            WHERE tenant_id = #{tenantId}
              AND agent_type IN ('assistant', 'workflow-dialogue')
              AND updated_at >= now() - interval '7 days'
            ORDER BY updated_at DESC, id DESC
            LIMIT 1
            """)
    Map<String, Object> selectLastChatSession(@Param("tenantId") Long tenantId);
}