package com.easysys.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.easysys.common.mybatis.JsonbStringTypeHandler;

import java.time.Instant;

/**
 * 驾驶舱图谱登记：八类知识领域（本体/技能/工具/MCP/子Agent/记忆/知识库/评测中心）的
 * 清单与状态管理。模块判别列 module + entry_key 唯一；内置目录同 entry_key 的用户行覆盖内置。
 */
@TableName("agent_graph_entry")
public class AgentGraphEntry {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;

    /** 知识领域：ONTOLOGY / SKILL / TOOL / MCP / SUBAGENT / MEMORY / KNOWLEDGE / EVALUATION。 */
    private String module;

    /** 模块内唯一标识（与内置目录合并键）。 */
    private String entryKey;

    private String name;
    private String description;

    /** 模块元数据 JSON（能力描述/参数 schema/来源等）。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String payload;

    /** ENABLED / DISABLED。 */
    private String status;

    private String version;
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

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public String getEntryKey() {
        return entryKey;
    }

    public void setEntryKey(String entryKey) {
        this.entryKey = entryKey;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
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