package com.easysys.api.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.easysys.api.entity.Event;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface EventMapper extends BaseMapper<Event> {

    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            INSERT INTO event (tenant_id, contact_id, event_name, payload, occurred_at)
            VALUES (#{tenantId}, #{contactId}, #{eventName}, #{payload}::jsonb, #{occurredAt})
            ON CONFLICT (tenant_id, contact_id, event_name, occurred_at) DO NOTHING
            """)
    int insertIgnore(Event event);

    /** contactId → 最近事件时间（无事件的人在结果中缺省）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            <script>
            SELECT contact_id, MAX(occurred_at) AS last_at FROM event
            WHERE tenant_id = #{tenantId} AND contact_id IN
            <foreach collection="contactIds" item="cid" open="(" separator="," close=")">#{cid}</foreach>
            GROUP BY contact_id
            </script>
            """)
    List<Map<String, Object>> selectLastActive(@Param("tenantId") Long tenantId,
                                               @Param("contactIds") List<Long> contactIds);

    /** 窗口 [start, end) 内活跃去重人数。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT COUNT(DISTINCT contact_id) FROM event "
            + "WHERE tenant_id = #{tenantId} AND occurred_at >= #{start} AND occurred_at < #{end}")
    long countDistinctActive(@Param("tenantId") Long tenantId,
                             @Param("start") Instant start, @Param("end") Instant end);

    /** 本窗口活跃且前一窗口也活跃（留存）的去重人数。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT COUNT(DISTINCT e.contact_id) FROM event e
            WHERE e.tenant_id = #{tenantId} AND e.occurred_at >= #{curStart} AND e.occurred_at < #{curEnd}
            AND EXISTS (SELECT 1 FROM event p
                        WHERE p.tenant_id = #{tenantId} AND p.contact_id = e.contact_id
                        AND p.occurred_at >= #{priorStart} AND p.occurred_at < #{priorEnd})
            """)
    long countDistinctRetained(@Param("tenantId") Long tenantId,
                               @Param("priorStart") Instant priorStart, @Param("priorEnd") Instant priorEnd,
                               @Param("curStart") Instant curStart, @Param("curEnd") Instant curEnd);
}