-- P3 修复：evaluation_human_review 的 UNIQUE (report_id, case_seq, metric, deleted) 与 @TableLogic 冲突
-- 逻辑删除（UPDATE deleted=TRUE）不释放复合唯一键：upsert 先软删旧行再插新行、deleteReview 再软删活行，
-- 同一分组会积累多条 deleted=TRUE 墓碑 → 再次删除撞唯一键 500（同 V7 先例）。
-- 改为仅约束未删除行的部分唯一索引：每分组至多一条活行，墓碑数量不限。
ALTER TABLE evaluation_human_review DROP CONSTRAINT evaluation_human_review_report_id_case_seq_metric_deleted_key;
CREATE UNIQUE INDEX uk_eval_human_review_group_active ON evaluation_human_review (report_id, case_seq, metric) WHERE NOT deleted;