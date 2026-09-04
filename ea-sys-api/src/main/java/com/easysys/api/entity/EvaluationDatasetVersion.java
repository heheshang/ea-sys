package com.easysys.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.easysys.common.mybatis.JsonbStringTypeHandler;

import java.time.Instant;

/**
 * 评测数据集版本：发布时刻工作区用例快照（不可变），run/task 绑定版本读取。
 * cases 为全字段用例快照 JSON 数组（seq 升序）；逻辑删除（deleted），version_no 单调递增不复用。
 */
@TableName("evaluation_dataset_version")
public class EvaluationDatasetVersion {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long datasetId;

    /** 数据集内版本号（单调递增，删除不复用）。 */
    private Integer versionNo;

    /** PUBLISHED（DRAFT 留后续）。 */
    private String status;

    /** 用例快照 JSON（全字段同构、按 seq 升序）。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String cases;

    /** 发布时刻评测器选择快照 JSON（本轮 null）。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String evaluators;

    /** 发布时间（快照生效时刻）。 */
    private Instant publishedAt;

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

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCases() {
        return cases;
    }

    public void setCases(String cases) {
        this.cases = cases;
    }

    public String getEvaluators() {
        return evaluators;
    }

    public void setEvaluators(String evaluators) {
        this.evaluators = evaluators;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
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