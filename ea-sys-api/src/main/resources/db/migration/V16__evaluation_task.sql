-- 评测中心：异步评测任务（H1）。任务承载一次批量评测：状态机 PENDING→RUNNING→COMPLETED/FAILED/CANCELED，
-- 逐样本进度（tested_cases/progress_pct）与逐样本明细（sample_results，见评测文档），完成后指向 evaluation_report。

CREATE TABLE evaluation_task (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL REFERENCES tenant (id),
    name          VARCHAR(255) NOT NULL,
    dataset_id    BIGINT       NOT NULL REFERENCES evaluation_dataset (id),
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    total_cases   INTEGER      NOT NULL DEFAULT 0,
    tested_cases  INTEGER      NOT NULL DEFAULT 0,
    progress_pct  NUMERIC(5, 2) NOT NULL DEFAULT 0,
    error_message TEXT,
    report_id     BIGINT REFERENCES evaluation_report (id),
    params        JSONB,
    sample_results JSONB,
    created_by    VARCHAR(64),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted       BOOLEAN      NOT NULL DEFAULT FALSE
);

COMMENT ON TABLE evaluation_task IS '评测任务：异步批量评测的状态机与逐样本结果';
COMMENT ON COLUMN evaluation_task.id IS '任务 ID';
COMMENT ON COLUMN evaluation_task.tenant_id IS '租户 ID';
COMMENT ON COLUMN evaluation_task.name IS '任务名称（数据集名派生）';
COMMENT ON COLUMN evaluation_task.dataset_id IS '评测数据集 ID';
COMMENT ON COLUMN evaluation_task.status IS 'PENDING/RUNNING/COMPLETED/FAILED/CANCELED';
COMMENT ON COLUMN evaluation_task.total_cases IS '总用例数（RUNNING 起跑时确定）';
COMMENT ON COLUMN evaluation_task.tested_cases IS '已判分用例数（逐样本进度）';
COMMENT ON COLUMN evaluation_task.progress_pct IS '进度百分比 0-100';
COMMENT ON COLUMN evaluation_task.error_message IS 'FAILED/CANCELED 原因';
COMMENT ON COLUMN evaluation_task.report_id IS '完成时指向 evaluation_report';
COMMENT ON COLUMN evaluation_task.params IS '运行参数快照 {evaluators,judgeRounds,mode,traceId}';
COMMENT ON COLUMN evaluation_task.sample_results IS '逐样本结果 [{seq,question,actual_response,mode,metrics[]}]';
COMMENT ON COLUMN evaluation_task.created_by IS '创建人';
COMMENT ON COLUMN evaluation_task.created_at IS '创建时间';
COMMENT ON COLUMN evaluation_task.updated_at IS '更新时间';
COMMENT ON COLUMN evaluation_task.deleted IS '逻辑删除标记（MyBatis-Plus @TableLogic）';

CREATE INDEX idx_evaluation_task_tenant_created ON evaluation_task (tenant_id, created_at DESC);
CREATE INDEX idx_evaluation_task_tenant_dataset ON evaluation_task (tenant_id, dataset_id);