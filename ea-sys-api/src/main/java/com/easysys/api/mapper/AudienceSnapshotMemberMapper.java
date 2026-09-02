package com.easysys.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.easysys.api.dto.audience.MemberView;
import com.easysys.api.entity.AudienceSnapshotMember;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AudienceSnapshotMemberMapper extends BaseMapper<AudienceSnapshotMember> {

    @Insert("""
            <script>
            INSERT INTO audience_snapshot_member (snapshot_id, contact_id) VALUES
            <foreach collection="ids" item="cid" separator=",">(#{snapshotId}, #{cid})</foreach>
            </script>
            """)
    int insertBatch(@Param("snapshotId") Long snapshotId, @Param("ids") List<Long> ids);

    IPage<MemberView> selectMembers(IPage<MemberView> page, @Param("snapshotId") Long snapshotId, @Param("tenantId") Long tenantId);

    /** 快照成员 contactId（无 tenant 列，隔离经 snapshot 间接保证）。 */
    @Select("SELECT contact_id FROM audience_snapshot_member WHERE snapshot_id = #{snapshotId} ORDER BY contact_id")
    List<Long> selectContactIds(@Param("snapshotId") Long snapshotId);

    /** 多个快照成员去重计数（执行人数）。 */
    @Select("""
            <script>
            SELECT COUNT(DISTINCT contact_id) FROM audience_snapshot_member WHERE snapshot_id IN
            <foreach collection="snapshotIds" item="sid" open="(" separator="," close=")">#{sid}</foreach>
            </script>
            """)
    long countDistinctContacts(@Param("snapshotIds") List<Long> snapshotIds);
}