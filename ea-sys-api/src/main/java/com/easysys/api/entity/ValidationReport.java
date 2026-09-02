package com.easysys.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.easysys.common.mybatis.JsonbStringTypeHandler;

import java.time.Instant;

/**
 * 计划导入校验报告（发布闸门依据）：上传的运营计划文件解析比对后的分级结果。
 * decision ∈ PASSED / WARNINGS / BLOCKED；report 为报告全文 JSON（回看）。
 */
@TableName("validation_report")
public class ValidationReport {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;

    /** 业务 id（workflow.ref_id），非版本行主键。 */
    private Long workflowId;

    /** PASSED / WARNINGS / BLOCKED。 */
    private String decision;

    /** 报告全文 JSON：plan_summary + dimensions[] + summary{conflicts,warnings,passed} + decision。 */
    @TableField(value = "\"report\"", typeHandler = JsonbStringTypeHandler.class)
    private String report;

    private String fileType;
    private String fileName;
    private String createdBy;
    private Instant createdAt;
    @TableLogic
    private Boolean deleted;

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

    public Long getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(Long workflowId) {
        this.workflowId = workflowId;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getReport() {
        return report;
    }

    public void setReport(String report) {
        this.report = report;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Boolean getDeleted() {
        return deleted;
    }

    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }
}