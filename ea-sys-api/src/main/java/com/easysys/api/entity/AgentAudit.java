package com.easysys.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.easysys.common.mybatis.JsonbStringTypeHandler;

import java.math.BigDecimal;
import java.time.Instant;

/** 智能体调用审计（追加写：schema 校验结果、置信度、降级原因、耗时）。 */
@TableName("audit_log")
public class AgentAudit {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String agentType;
    private String action;
    private String status;
    private String reason;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String inputSummary;
    @TableField(value = "\"output\"", typeHandler = JsonbStringTypeHandler.class)
    private String output;
    private Boolean schemaValid;
    private String strategyVersion;
    private BigDecimal confidence;
    private String model;
    private Integer tokens;
    private Long durationMs;
    private BigDecimal cost;
    private String operator;
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

    public String getAgentType() {
        return agentType;
    }

    public void setAgentType(String agentType) {
        this.agentType = agentType;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getInputSummary() {
        return inputSummary;
    }

    public void setInputSummary(String inputSummary) {
        this.inputSummary = inputSummary;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public Boolean getSchemaValid() {
        return schemaValid;
    }

    public void setSchemaValid(Boolean schemaValid) {
        this.schemaValid = schemaValid;
    }

    public String getStrategyVersion() {
        return strategyVersion;
    }

    public void setStrategyVersion(String strategyVersion) {
        this.strategyVersion = strategyVersion;
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

    public Integer getTokens() {
        return tokens;
    }

    public void setTokens(Integer tokens) {
        this.tokens = tokens;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public BigDecimal getCost() {
        return cost;
    }

    public void setCost(BigDecimal cost) {
        this.cost = cost;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}