package com.easysys.api.dto.workflow;

import jakarta.validation.constraints.NotNull;

/**
 * 干跑请求：对已发布版本 + 冻结快照成员模拟执行，不产生真实触达。
 */
public record DryRunRequest(@NotNull Long audienceSnapshotId) {
}