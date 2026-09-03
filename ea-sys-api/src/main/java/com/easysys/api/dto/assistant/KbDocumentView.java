package com.easysys.api.dto.assistant;

import java.time.Instant;

/** 知识库文档行（列表 / 上传结果）：解析状态、分块数、错误信息。 */
public record KbDocumentView(
        Long id,
        String name,
        String contentType,
        Long sizeBytes,
        String status,
        String error,
        Integer chunkCount,
        Instant createdAt) {
}