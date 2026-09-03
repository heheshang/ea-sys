package com.easysys.api.dto.workflow;

/**
 * 干跑请求：对已发布版本 + 冻结快照成员模拟执行，不产生真实触达。
 * audienceSnapshotId 可缺省——画布含 AUDIENCE 人群节点时成员由节点圈选，此参数仅为旧流程兜底。
 */
public record DryRunRequest(Long audienceSnapshotId) {
}