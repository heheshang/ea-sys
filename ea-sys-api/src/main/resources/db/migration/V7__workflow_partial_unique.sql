-- M2 修复：workflow_node / workflow_edge 的 UNIQUE 约束与 MyBatis-Plus @TableLogic 冲突
-- 逻辑删除（UPDATE deleted=TRUE）不释放 UNIQUE 约束，导致 replaceCanvas 先删旧行再插新行时
-- 撞 uk_workflow_node_key / uk_workflow_edge。改为部分唯一索引（仅约束未删除行）。

ALTER TABLE workflow_node DROP CONSTRAINT uk_workflow_node_key;
CREATE UNIQUE INDEX uk_workflow_node_key_active ON workflow_node (workflow_id, version, node_key) WHERE NOT deleted;

ALTER TABLE workflow_edge DROP CONSTRAINT uk_workflow_edge;
CREATE UNIQUE INDEX uk_workflow_edge_active ON workflow_edge (workflow_id, version, source_key, target_key) WHERE NOT deleted;