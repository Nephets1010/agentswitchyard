-- agentswitchyard V2 索引
-- ★ 核心唯一索引：幂等判定的物理载体。并发同键插入在数据库层只允许一行成功。

CREATE UNIQUE INDEX ux_step_record_idem
    ON step_record (flow_instance_id, step_id, idem_key);

-- 崩溃恢复扫描：listInstancesInState(RUNNING)
CREATE INDEX ix_flow_instance_state
    ON flow_instance (state);

-- 步骤按序读取
CREATE INDEX ix_step_record_instance
    ON step_record (flow_instance_id, seq);

-- 审批待办列表
CREATE INDEX ix_approval_role_state
    ON approval_record (approver_role, state);

-- 轨迹导出：按实例取审批留痕
CREATE INDEX ix_approval_instance
    ON approval_record (flow_instance_id);

-- 回放命中：按请求摘要查录制
CREATE INDEX ix_replay_blob_digest
    ON replay_blob (flow_instance_id, request_digest);
