package com.easysys.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.easysys.api.entity.ChannelConfig;

/** 通道凭据配置查询（租户插件自动附加 tenant_id 过滤）。 */
public interface ChannelConfigMapper extends BaseMapper<ChannelConfig> {
}