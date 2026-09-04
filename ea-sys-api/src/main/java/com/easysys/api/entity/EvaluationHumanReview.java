package com.easysys.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 人工评测回填（Human 黄金标准闭环）：报告 + 用例 + 指标维度的人工评分/结论，
 * 供 calibration 对照自动分。同 (report_id, case_seq, metric) 软删旧行后插新行实现 upsert。
 */
@TableName("evaluation_human_review")
public class EvaluationHumanReview {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long reportId;
    private Integer caseSeq;
    /** 指标维度；缺省 '*' 表示整例评级。 */
    private String metric;
    /** 人工评分 0-1（可空：仅结论不评分）。 */
    private BigDecimal score;
    /** PASS / WARN / FAIL（可空：未给则由 score 派生）。 */
    private String verdict;
    private String note;
    private String reviewer;
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

    public Long getReportId() {
        return reportId;
    }

    public void setReportId(Long reportId) {
        this.reportId = reportId;
    }

    public Integer getCaseSeq() {
        return caseSeq;
    }

    public void setCaseSeq(Integer caseSeq) {
        this.caseSeq = caseSeq;
    }

    public String getMetric() {
        return metric;
    }

    public void setMetric(String metric) {
        this.metric = metric;
    }

    public BigDecimal getScore() {
        return score;
    }

    public void setScore(BigDecimal score) {
        this.score = score;
    }

    public String getVerdict() {
        return verdict;
    }

    public void setVerdict(String verdict) {
        this.verdict = verdict;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getReviewer() {
        return reviewer;
    }

    public void setReviewer(String reviewer) {
        this.reviewer = reviewer;
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