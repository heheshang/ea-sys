-- V11：企业知识库（AI 智能客服 RAG 数据层）
-- 文档与其分块：解析 → 切片 → CJK 分词 → 词频 JSONB 落库；检索在 Java 侧按词频打分（确定性，无向量库）。
-- 文档为逻辑删除（全局 logic-delete），分块随文档物理删除。

CREATE TABLE kb_document (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT       NOT NULL REFERENCES tenant (id),
    name         VARCHAR(255) NOT NULL,
    content_type VARCHAR(64)  NOT NULL,
    size_bytes   BIGINT       NOT NULL DEFAULT 0,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ready',
    error        TEXT,
    chunk_count  INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted      BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE TABLE kb_document_chunk (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL REFERENCES tenant (id),
    document_id BIGINT       NOT NULL REFERENCES kb_document (id),
    seq         INT          NOT NULL,
    content     TEXT         NOT NULL,
    tokens      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_kb_chunk_doc_seq UNIQUE (document_id, seq)
);

CREATE INDEX idx_kb_document_tenant ON kb_document (tenant_id);
CREATE INDEX idx_kb_chunk_document ON kb_document_chunk (tenant_id, document_id);
-- jsonb 键存在预筛（tokens ?| ARRAY[...]）：检索先按查询词拉候选分块，再 Java 侧 BM25 打分
CREATE INDEX idx_kb_chunk_tokens_gin ON kb_document_chunk USING GIN (tokens);

COMMENT ON TABLE kb_document IS '知识库文档（解析状态 + 分块计数）';
COMMENT ON TABLE kb_document_chunk IS '知识库文档分块（正文 + 词频 JSONB，检索打分单元）';