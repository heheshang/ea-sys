package com.easysys.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.easysys.api.entity.KbDocument;

/** 知识库文档查询（租户插件自动附加 tenant_id 过滤 + 逻辑删除过滤）。 */
public interface KbDocumentMapper extends BaseMapper<KbDocument> {
}