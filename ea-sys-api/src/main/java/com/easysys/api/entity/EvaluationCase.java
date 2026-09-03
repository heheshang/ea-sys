package com.easysys.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.easysys.common.mybatis.JsonbStringTypeHandler;

import java.time.Instant;

/**
 * 评测用例：用户提示词 + 期望答案 + 可选系统提示词/工具定义/期望工具调用/OpenJudge 预置响应。
 */
@TableName("evaluation_case")
public class EvaluationCase {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long datasetId;

    /** 数据集内排序序号。 */
    private Integer seq;

    /** 用户提示词。 */
    private String question;

    /** 系统提示词（可选）。 */
    private String systemPrompt;

    /** 期望答案 JSON（判分基准：字符串/数字/对象均可）。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String expectedOutput;

    /** 工具定义 JSON（可选）。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String toolSchema;

    /** 期望工具调用 JSON {name, args}（可选）。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String expectedTool;

    /** OpenJudge 预置响应文本：直接作为实际响应判分，跳过被测智能体执行。 */
    private String providedResponse;

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

    public Integer getSeq() {
        return seq;
    }

    public void setSeq(Integer seq) {
        this.seq = seq;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getExpectedOutput() {
        return expectedOutput;
    }

    public void setExpectedOutput(String expectedOutput) {
        this.expectedOutput = expectedOutput;
    }

    public String getToolSchema() {
        return toolSchema;
    }

    public void setToolSchema(String toolSchema) {
        this.toolSchema = toolSchema;
    }

    public String getExpectedTool() {
        return expectedTool;
    }

    public void setExpectedTool(String expectedTool) {
        this.expectedTool = expectedTool;
    }

    public String getProvidedResponse() {
        return providedResponse;
    }

    public void setProvidedResponse(String providedResponse) {
        this.providedResponse = providedResponse;
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