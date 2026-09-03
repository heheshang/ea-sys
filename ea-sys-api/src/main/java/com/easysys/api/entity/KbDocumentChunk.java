package com.easysys.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.easysys.api.assistant.JsonbTypeHandler;

import java.time.Instant;

/**
 * 知识库文档分块：检索打分单元。tokens 为词频 JSONB（键 = 词元，值 = 词频），
 * 由解析时 CJK 分词（拉丁词 + 中文二元组）产出；随文档删除物理清除（无逻辑删除）。
 */
@TableName("kb_document_chunk")
public class KbDocumentChunk {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long documentId;
    private Integer seq;
    private String content;
    /** 词频 JSONB：写侧以 OTHER 类型发送、由 PostgreSQL 隐式转 jsonb。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String tokens;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public Integer getSeq() {
        return seq;
    }

    public void setSeq(Integer seq) {
        this.seq = seq;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getTokens() {
        return tokens;
    }

    public void setTokens(String tokens) {
        this.tokens = tokens;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}