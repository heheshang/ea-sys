package com.easysys.api.dto.evaluation;

import java.util.List;

/** 评测运行请求：数据集 + 评测器子集（缺省 = 全量 11 个内置评测器）。 */
public record EvaluationRunRequest(
        Long datasetId,
        List<String> evaluators) {
}