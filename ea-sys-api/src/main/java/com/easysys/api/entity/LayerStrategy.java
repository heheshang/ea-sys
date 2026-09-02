package com.easysys.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.easysys.common.mybatis.JsonbStringTypeHandler;

import java.math.BigDecimal;
import java.time.Instant;

/** 租户分层策略（AGENT_SPLIT 分流依据；draft → published 发布闸门，单租户仅一份生效）。 */
@TableName("layer_strategy")
public class LayerStrategy {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String name;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String dimensions;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String routeOrder;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String strategy;
    private String source;
    private String status;
    private String strategyVersion;
    private BigDecimal confidence;
    private String createdBy;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant publishedAt;
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

    public String getDimensions() {
        return dimensions;
    }

    public void setDimensions(String dimensions) {
        this.dimensions = dimensions;
    }

    public String getRouteOrder() {
        return routeOrder;
    }

    public void setRouteOrder(String routeOrder) {
        this.routeOrder = routeOrder;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Boolean getDeleted() {
        return deleted;
    }

    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }
}