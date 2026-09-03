package com.easysys.api.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.easysys.api.entity.AgentAudit;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

public interface AgentAuditMapper extends BaseMapper<AgentAudit> {

    /** 驾驶舱总览：按 agent_type 聚合近 7 天调用（绕过租户插件，显式 tenant_id 过滤）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT agent_type                                              AS agent_type,
                   COUNT(*)                                                AS calls,
                   COUNT(*) FILTER (WHERE status = 'SUCCESS')              AS success,
                   COUNT(*) FILTER (WHERE status = 'FALLBACK')             AS fallback,
                   COUNT(*) FILTER (WHERE status = 'ERROR')                AS error,
                   COUNT(*) FILTER (WHERE schema_valid = TRUE)             AS schema_valid,
                   COALESCE(AVG(duration_ms), 0)                           AS avg_duration_ms,
                   COALESCE(SUM(tokens), 0)                                AS sum_tokens,
                   COALESCE(SUM(cost), 0)                                  AS sum_cost
            FROM audit_log
            WHERE tenant_id = #{tenantId} AND created_at >= now() - interval '7 days'
            GROUP BY agent_type
            ORDER BY calls DESC
            """)
    List<Map<String, Object>> selectAgentStats(@Param("tenantId") Long tenantId);

    /** 驾驶舱总览：按 model 聚合近 7 天调用。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT model                                                     AS model,
                   COUNT(*)                                                  AS calls,
                   COUNT(*) FILTER (WHERE status = 'SUCCESS')                AS success,
                   COUNT(*) FILTER (WHERE status = 'FALLBACK')               AS fallback,
                   COUNT(*) FILTER (WHERE status = 'ERROR')                  AS error,
                   COUNT(*) FILTER (WHERE schema_valid = TRUE)               AS schema_valid,
                   COALESCE(AVG(duration_ms), 0)                             AS avg_duration_ms,
                   COALESCE(SUM(tokens), 0)                                  AS sum_tokens,
                   COALESCE(SUM(cost), 0)                                    AS sum_cost
            FROM audit_log
            WHERE tenant_id = #{tenantId} AND created_at >= now() - interval '7 days'
            GROUP BY model
            ORDER BY calls DESC
            """)
    List<Map<String, Object>> selectModelStats(@Param("tenantId") Long tenantId);

    /** 驾驶舱总览：按天聚合近 7 天调用（trend）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT to_char(created_at, 'YYYY-MM-DD')                        AS day,
                   COUNT(*)                                                 AS calls,
                   COUNT(*) FILTER (WHERE status = 'SUCCESS')               AS success,
                   COALESCE(SUM(tokens), 0)                                 AS sum_tokens,
                   COALESCE(SUM(cost), 0)                                   AS sum_cost
            FROM audit_log
            WHERE tenant_id = #{tenantId} AND created_at >= now() - interval '7 days'
            GROUP BY day
            ORDER BY day
            """)
    List<Map<String, Object>> selectDailyTrend(@Param("tenantId") Long tenantId);

    /** 驾驶舱 LLM 追踪：最近 N 条调用（时间倒序）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT id, tenant_id, agent_type, action, status, reason, schema_valid, strategy_version,
                   confidence, model, tokens, duration_ms, cost, operator, created_at
            FROM audit_log
            WHERE tenant_id = #{tenantId}
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<AgentAudit> selectRecent(@Param("tenantId") Long tenantId, @Param("limit") int limit);
}