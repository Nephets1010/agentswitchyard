-- agentswitchyard V3 审计列
-- 为三张核心账本表补充操作者审计列（谁创建 / 最后谁改）。
-- 首版由执行引擎统一写入 system；接入组织身份后由 starter 透传。

ALTER TABLE flow_instance ADD COLUMN created_by VARCHAR(64);
ALTER TABLE flow_instance ADD COLUMN updated_by VARCHAR(64);

ALTER TABLE step_record ADD COLUMN created_by VARCHAR(64);
ALTER TABLE step_record ADD COLUMN updated_by VARCHAR(64);

ALTER TABLE approval_record ADD COLUMN created_by VARCHAR(64);
ALTER TABLE approval_record ADD COLUMN updated_by VARCHAR(64);
