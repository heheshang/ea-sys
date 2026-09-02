package com.easysys.api.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.easysys.api.dto.audience.AudienceRow;
import com.easysys.api.entity.Audience;

public interface AudienceMapper extends BaseMapper<Audience> {

    /**
     * 人群分页，带最近一次圈选快照摘要（LATERAL 一条 SQL，避免 N+1）。
     */
    IPage<AudienceRow> selectAudiencePage(IPage<AudienceRow> page, Long tenantId);
}