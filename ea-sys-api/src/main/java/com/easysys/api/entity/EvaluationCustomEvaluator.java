package com.easysys.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.easysys.common.mybatis.JsonbStringTypeHandler;

import java.time.Instant;

/**
 * 自定义评测器：LLM-Judge（judge_prompt 可配提示词）与规则评测器（Java 参数化规则，不引 Python）。
 * 指标名 = custom_{id}；rule 类逐用例确定性判分，llm_judge 类 LLM 启用时真实打分、停用时降级近似。
 */
@TableName("evaluation_custom_evaluator")
public class EvaluationCustomEvaluator {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String name;
    /** rule / llm_judge。 */
    private String category;
    private String description;
    /** 规则类型：keyword_contains / regex_match / length_between（仅 rule 类）。 */
    private String ruleType;
    /** 规则参数 JSON：{keywords, all, prohibit} / {pattern} / {min, max}。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String params;
    /** LLM-Judge 提示词模板（含 {question}/{response}/{reference} 占位）。 */
    private String judgePrompt;
    /** ENABLED / DISABLED。 */
    private String status;
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

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getRuleType() {
        return ruleType;
    }

    public void setRuleType(String ruleType) {
        this.ruleType = ruleType;
    }

    public String getParams() {
        return params;
    }

    public void setParams(String params) {
        this.params = params;
    }

    public String getJudgePrompt() {
        return judgePrompt;
    }

    public void setJudgePrompt(String judgePrompt) {
        this.judgePrompt = judgePrompt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

    /** 指标名（评测器白名单引用标识）。 */
    public String metric() {
        return "custom_" + id;
    }
}