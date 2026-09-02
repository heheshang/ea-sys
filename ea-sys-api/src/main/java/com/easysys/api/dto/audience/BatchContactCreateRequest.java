package com.easysys.api.dto.audience;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 批量添加联系人请求。
 * 数据随机生成：externalId/手机号随机唯一，画像属性随机（churn_risk、level），便于圈选验证。
 */
public record BatchContactCreateRequest(
        @Min(value = 1, message = "数量至少为 1") @Max(value = 5000, message = "单次最多 5000 个") int count) {
}