package com.easysys.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.easysys.common.mybatis.JsonbStringTypeHandler;

import java.time.Instant;

/**
 * 评测逐轮转录：执行过程中的每一轮用户/助手/工具消息，回看多轮执行轨迹与工具调用明细。
 *
 * <p>无逻辑删除列——转录依附报告存在（report_id 外键），报告删除即不可达，无需独立软删。
 * role 取值 USER / ASSISTANT / TOOL（SYSTEM 消息转录时跳过）。</p>
 */
@TableName("evaluation_transcript")
public class EvaluationTranscript {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long reportId;

    /** 用例序号（同一报告内按 seq 定位）。 */
    private Integer caseSeq;

    /** 轮次：1-based 调用序号（多轮 = 同一 sessionId 连续调用被测智能体）。 */
    private Integer turnNo;

    /** USER / ASSISTANT / TOOL。 */
    private String role;

    /** 消息文本内容（USER 提问 / ASSISTANT 回复正文）。 */
    private String text;

    /** 推理可见思考（截 4000）。 */
    private String thinking;

    /** 工具调用 JSON {name, args}，仅 TOOL 消息含 ToolUseBlock 时写入。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String toolUse;

    /** 工具结果 JSON {name, state, output}，仅 TOOL 消息含 ToolResultBlock 时写入。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String toolResult;

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

    public Integer getTurnNo() {
        return turnNo;
    }

    public void setTurnNo(Integer turnNo) {
        this.turnNo = turnNo;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getThinking() {
        return thinking;
    }

    public void setThinking(String thinking) {
        this.thinking = thinking;
    }

    public String getToolUse() {
        return toolUse;
    }

    public void setToolUse(String toolUse) {
        this.toolUse = toolUse;
    }

    public String getToolResult() {
        return toolResult;
    }

    public void setToolResult(String toolResult) {
        this.toolResult = toolResult;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}