package com.easysys.engine.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.easysys.engine.entity.DeliveryRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Mapper
public interface DeliveryRecordMapper extends BaseMapper<DeliveryRecord> {

    /** 触达人数：工作流（null=租户全量）非 dry-run 执行中 SENT/DELIVERED 去重联系人。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            <script>
            SELECT COUNT(DISTINCT d.contact_id) FROM delivery_record d
            JOIN execution e ON e.id = d.execution_id
            WHERE d.tenant_id = #{tenantId} AND e.dry_run = false AND d.deleted = false
            AND d.status IN ('SENT','DELIVERED')
            <if test="workflowId != null">AND e.workflow_id = #{workflowId}</if>
            </script>
            """)
    long countDistinctReached(@Param("tenantId") Long tenantId, @Param("workflowId") Long workflowId);

    /** 渠道效果：since 以来每渠道 总数/送达/失败/去重触达人数；eventName 非空附加转化人数。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            <script>
            SELECT d.channel,
                   COUNT(*) AS total,
                   COUNT(*) FILTER (WHERE d.status IN ('SENT','DELIVERED')) AS sent,
                   COUNT(*) FILTER (WHERE d.status = 'FAILED') AS failed,
                   COUNT(DISTINCT d.contact_id) AS contacts
                   <if test="eventName != null">,
                   COUNT(DISTINCT e.contact_id) AS converted</if>
            FROM delivery_record d
            <if test="eventName != null">
            LEFT JOIN event e ON e.tenant_id = d.tenant_id AND e.contact_id = d.contact_id
                AND e.event_name = #{eventName} AND e.occurred_at >= #{since}
            </if>
            WHERE d.tenant_id = #{tenantId} AND d.created_at >= #{since} AND d.deleted = false
            GROUP BY d.channel ORDER BY d.channel
            </script>
            """)
    List<Map<String, Object>> selectChannelStats(@Param("tenantId") Long tenantId,
                                                 @Param("since") Instant since,
                                                 @Param("eventName") String eventName);

    /** 单次执行的触达人数。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT COUNT(DISTINCT contact_id) FROM delivery_record "
            + "WHERE tenant_id = #{tenantId} AND execution_id = #{executionId} "
            + "AND status IN ('SENT','DELIVERED') AND deleted = false")
    long countDistinctByExecution(@Param("tenantId") Long tenantId, @Param("executionId") Long executionId);

    /** 单次执行的触达人数中，在 [windowStart, windowEnd] 窗口内活跃（有行为事件）的人数。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT COUNT(DISTINCT d.contact_id) FROM delivery_record d
            JOIN event e ON e.tenant_id = d.tenant_id AND e.contact_id = d.contact_id
            WHERE d.tenant_id = #{tenantId} AND d.execution_id = #{executionId}
            AND d.status IN ('SENT','DELIVERED') AND d.deleted = false
            AND e.occurred_at >= #{windowStart} AND e.occurred_at <= #{windowEnd}
            """)
    long countRetainedByExecution(@Param("tenantId") Long tenantId, @Param("executionId") Long executionId,
                                  @Param("windowStart") Instant windowStart, @Param("windowEnd") Instant windowEnd);
}