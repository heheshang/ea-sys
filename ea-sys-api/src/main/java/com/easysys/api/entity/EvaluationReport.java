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
 * 评测报告：批量运行评测后的指标均值 + 分级发现 + 汇总 verdict，回看与对比基线。
 * metrics/findings/summary 为报告 JSON；confidence 为规则确定性置信度（恒 1.0）。
 */
@TableName("evaluation_report")
public class EvaluationReport {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long datasetId;
    private String name;

    /** 数据集用例总数。 */
    private Integer totalCases;

    /** 实际判分用例数。 */
    private Integer testedCases;

    /** 指标均值 JSON：[{metric, category, avg_score, passed_count, applicable_count}]。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String metrics;

    /** 分级发现 JSON：[{level, dimension, detail, suggestion}]。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String findings;

    /** 汇总 JSON：{score, verdict}。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String summary;

    /** 报告置信度 0-1（确定性规则模式恒 1.0）。 */
    private BigDecimal confidence;

    /** 判分模型位：deterministic / 模型 id。 */
    private String model;

    /** openjudge / execute。 */
    private String mode;

    /** LLM 判分轮次 1-5（多次取均值）。 */
    private Integer judgeRounds;

    /** 运行追踪 ID（驾驶舱 LLM 调用联动）。 */
    private String traceId;

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

    public Long getDatasetId() {
        return datasetId;
    }

    public void setDatasetId(Long datasetId) {
        this.datasetId = datasetId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public String getMetrics() {
        return metrics;
    }

    public void setMetrics(String metrics) {
        this.metrics = metrics;
    }

    public String getFindings() {
        return findings;
    }

    public void setFindings(String findings) {
        this.findings = findings;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public Integer getJudgeRounds() {
        return judgeRounds;
    }

    public void setJudgeRounds(Integer judgeRounds) {
        this.judgeRounds = judgeRounds;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
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