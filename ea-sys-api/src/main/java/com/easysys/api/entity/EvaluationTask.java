package com.easysys.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.easysys.common.mybatis.JsonbStringTypeHandler;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 评测任务（H1 异步化）：一次批量评测的状态机与逐样本结果。
 * 状态机 PENDING→RUNNING→COMPLETED/FAILED/CANCELED；progress_pct 随逐样本判分单调递增；
 * 完成后 report_id 指向 evaluation_report；params 为运行参数快照，sample_results 为逐样本明细。
 */
@TableName("evaluation_task")
public class EvaluationTask {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String name;
    private Long datasetId;
    /** PENDING / RUNNING / COMPLETED / FAILED / CANCELED。 */
    private String status;
    private Integer totalCases;
    private Integer testedCases;
    private BigDecimal progressPct;
    private String errorMessage;
    private Long reportId;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String params;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String sampleResults;
    private String createdBy;
    private Instant createdAt;
    private Instant updatedAt;
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getDatasetId() {
        return datasetId;
    }

    public void setDatasetId(Long datasetId) {
        this.datasetId = datasetId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getTotalCases() {
        return totalCases;
    }

    public void setTotalCases(Integer totalCases) {
        this.totalCases = totalCases;
    }

    public Integer getTestedCases() {
        return testedCases;
    }

    public void setTestedCases(Integer testedCases) {
        this.testedCases = testedCases;
    }

    public BigDecimal getProgressPct() {
        return progressPct;
    }

    public void setProgressPct(BigDecimal progressPct) {
        this.progressPct = progressPct;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Long getReportId() {
        return reportId;
    }

    public void setReportId(Long reportId) {
        this.reportId = reportId;
    }

    public String getParams() {
        return params;
    }

    public void setParams(String params) {
        this.params = params;
    }

    public String getSampleResults() {
        return sampleResults;
    }

    public void setSampleResults(String sampleResults) {
        this.sampleResults = sampleResults;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Boolean getDeleted() {
        return deleted;
    }

    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }
}