package com.easysys.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.easysys.api.entity.EvaluationHumanReview;

/** 人工评测回填：同 (report_id, case_seq, metric) 软删旧行 + 插新行 upsert（MyBatis-Plus 自动过滤逻辑删除）。 */
public interface EvaluationHumanReviewMapper extends BaseMapper<EvaluationHumanReview> {
}