package com.easysys.api.dto.evaluation;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 人工评测回填视图：单条人工评审 + 自动分对齐（autoScore 派生，来源报告关联任务逐样本）。
 * auto 为 null 表示该用例无逐样本自动分（同步 run 报告或该指标不适用）。
 */
public record HumanReviewView(
        Long id,
        Long reportId,
        Integer caseSeq,
        String metric,
        BigDecimal score,
        String verdict,
        String note,
        String reviewer,
        Double auto,
        Instant createdAt) {

    /** 提交人工评审请求：caseSeq 必填；metric 缺省 '*'；score∈[0,1]；verdict 缺省由 score 派生。 */
    public record SaveRequest(
            Integer caseSeq,
            String metric,
            BigDecimal score,
            String verdict,
            String note) {
    }
}