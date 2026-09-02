package com.easysys.api.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.easysys.api.entity.AudienceSnapshot;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AudienceSnapshotMapper extends BaseMapper<AudienceSnapshot> {

    /** 就绪快照的圈选人数合计（按 snapshot id 集合）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            <script>
            SELECT COALESCE(SUM(member_count), 0) FROM audience_snapshot
            WHERE tenant_id = #{tenantId} AND status = 'ready' AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    long sumMemberCount(@Param("tenantId") Long tenantId, @Param("ids") List<Long> ids);
}