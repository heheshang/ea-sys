-- M2 发布记录：workflow 表补 published_by（发布人），支撑发布历史查询。
-- 历史版本行之前发布时未记发布人，回填为空字符串；后续发布写入当前操作者。

ALTER TABLE workflow ADD COLUMN published_by VARCHAR(64);