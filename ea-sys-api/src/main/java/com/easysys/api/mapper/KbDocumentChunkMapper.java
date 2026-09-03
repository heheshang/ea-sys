package com.easysys.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.easysys.api.entity.KbDocumentChunk;

/** 知识库分块查询（租户插件自动附加 tenant_id 过滤）。 */
public interface KbDocumentChunkMapper extends BaseMapper<KbDocumentChunk> {
}