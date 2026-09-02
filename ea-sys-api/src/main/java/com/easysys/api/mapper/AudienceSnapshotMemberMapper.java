package com.easysys.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.easysys.api.dto.audience.MemberView;
import com.easysys.api.entity.AudienceSnapshotMember;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

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
}