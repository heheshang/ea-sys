package com.easysys.api.dto.assistant;

/** 知识库检索命中：文档名 + 段落 + 相关度（供引用式回答与前端命中卡片）。 */
public record KbHit(
        Long documentId,
        String documentName,
        Integer seq,
        String content,
        double score) {
}