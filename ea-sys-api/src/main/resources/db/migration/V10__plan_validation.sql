-- M7：计划导入校验（发布前置）
-- validation_report：运营计划文件导入后的分级校验报告（PASSED / WARNINGS / BLOCKED）
-- 发布闸门：决策 BLOCKED 阻止发布（WorkflowService.publish 前置检查最新报告）
-- 报告全文 JSON 落库可回看；解析与比对审计走 audit_log（agent_type=plan_validation）

CREATE TABLE validation_report (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT       NOT NULL REFERENCES tenant (id),
    workflow_id  BIGINT       NOT NULL,                -- 业务 id（ref_id），非版本行主键
    decision     VARCHAR(16)  NOT NULL,                -- PASSED / WARNINGS / BLOCKED
    report       JSONB        NOT NULL,                -- 报告全文（plan_summary/dimensions/summary/decision）
    file_type    VARCHAR(8)   NOT NULL,                -- xlsx / csv
    file_name    VARCHAR(255),
    created_by   VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted      BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_validation_report_workflow ON validation_report (tenant_id, workflow_id, created_at DESC);

COMMENT ON TABLE validation_report IS '计划导入校验报告（发布闸门依据，BLOCKED 阻止发布）';
COMMENT ON COLUMN validation_report.report IS '校验报告 JSON：plan_summary + dimensions[] + summary{conflicts,warnings,passed} + decision';