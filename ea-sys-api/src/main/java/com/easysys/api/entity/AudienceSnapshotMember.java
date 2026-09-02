package com.easysys.api.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 快照成员，联合主键 (snapshot_id, contact_id)，无 tenant_id（隔离随 snapshot）。
 * 只读表：M1 仅 insert + select。
 */
@TableName("audience_snapshot_member")
public class AudienceSnapshotMember {

    private Long snapshotId;
    private Long contactId;

    public Long getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(Long snapshotId) {
        this.snapshotId = snapshotId;
    }

    public Long getContactId() {
        return contactId;
    }

    public void setContactId(Long contactId) {
        this.contactId = contactId;
    }
}