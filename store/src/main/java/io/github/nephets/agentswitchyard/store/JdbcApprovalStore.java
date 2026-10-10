/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.store;

import io.github.nephets.agentswitchyard.core.approval.ApprovalRecord;
import io.github.nephets.agentswitchyard.core.approval.ApprovalState;
import io.github.nephets.agentswitchyard.core.approval.ApprovalStore;
import io.github.nephets.agentswitchyard.core.approval.ApprovalTimeoutPolicy;
import io.github.nephets.agentswitchyard.store.support.IdempotentInsert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * {@link ApprovalStore} 的 JDBC 实现。
 *
 * <p>★ {@link #compareAndDecide} 是单条条件 UPDATE（{@code WHERE state = ?}）：
 * 数据库行锁保证并发决策<b>只有第一个生效</b>（「并发审批只生效一次」硬验收的持久化层依据）。
 */
public final class JdbcApprovalStore implements ApprovalStore {

    private static final List<String> APPROVAL_COLUMNS =
            List.of(
                    "approval_id",
                    "flow_instance_id",
                    "step_id",
                    "approver_role",
                    "state",
                    "approver",
                    "note",
                    "reason",
                    "degraded",
                    "requested_at",
                    "decided_at",
                    "due_at",
                    "timeout_policy");

    private final JdbcTemplate jdbc;
    private final IdempotentInsert saveInsert;

    /**
     * 创建 JDBC 审批存储。
     *
     * @param jdbc Spring {@link JdbcTemplate}
     * @param dialect 方言（影响幂等保存的 SQL 形态）
     */
    public JdbcApprovalStore(JdbcTemplate jdbc, Dialect dialect) {
        this.jdbc = jdbc;
        this.saveInsert =
                IdempotentInsert.of(
                        dialect, "approval_record", APPROVAL_COLUMNS, List.of("approval_id"));
    }

    @Override
    public void save(ApprovalRecord record) {
        // 幂等保存：同 approvalId 重复保存保留首行（挂起只发生一次）
        List<Object> values = new java.util.ArrayList<>();
        values.add(record.approvalId());
        values.add(record.flowInstanceId());
        values.add(record.stepId());
        values.add(record.approverRole());
        values.add(record.state().name());
        values.add(record.approver());
        values.add(record.note());
        values.add(record.reason());
        values.add(record.degraded());
        values.add(Timestamp.from(record.requestedAt()));
        values.add(record.decidedAt() == null ? null : Timestamp.from(record.decidedAt()));
        values.add(record.dueAt() == null ? null : Timestamp.from(record.dueAt()));
        values.add(record.timeoutPolicy().name());
        List<Object> params = new java.util.ArrayList<>();
        if (saveInsert.leadingParams() > 0) {
            // MERGE USING（H2/达梦）：冲突键 approval_id 在前（列 0）
            params.add(values.get(0));
        }
        params.addAll(values);
        jdbc.update(saveInsert.sql(), params.toArray());
    }

    @Override
    public Optional<ApprovalRecord> find(String approvalId) {
        List<ApprovalRecord> rows =
                jdbc.query(APPROVAL_SELECT + " WHERE approval_id = ?", APPROVAL_MAPPER, approvalId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public boolean compareAndDecide(
            String approvalId,
            ApprovalState expected,
            ApprovalState target,
            String approver,
            String note,
            boolean degraded,
            java.time.Instant at) {
        return jdbc.update(
                        "UPDATE approval_record SET state = ?, approver = ?, note = ?, degraded = ?, decided_at = ?"
                                + " WHERE approval_id = ? AND state = ?",
                        target.name(),
                        approver,
                        note,
                        degraded,
                        Timestamp.from(at),
                        approvalId,
                        expected.name())
                == 1;
    }

    @Override
    public List<ApprovalRecord> listPending(String approverRole) {
        return jdbc.query(
                APPROVAL_SELECT
                        + " WHERE approver_role = ? AND state = 'PENDING' ORDER BY requested_at",
                APPROVAL_MAPPER,
                approverRole);
    }

    @Override
    public List<ApprovalRecord> listAllPending() {
        return jdbc.query(
                APPROVAL_SELECT + " WHERE state = 'PENDING' ORDER BY requested_at",
                APPROVAL_MAPPER);
    }

    @Override
    public List<ApprovalRecord> listByInstance(String flowInstanceId) {
        return jdbc.query(
                APPROVAL_SELECT + " WHERE flow_instance_id = ? ORDER BY requested_at",
                APPROVAL_MAPPER,
                flowInstanceId);
    }

    private static final String APPROVAL_SELECT =
            "SELECT approval_id, flow_instance_id, step_id, approver_role, state, approver, note, reason,"
                    + " degraded, requested_at, decided_at, due_at, timeout_policy FROM approval_record";

    private static final RowMapper<ApprovalRecord> APPROVAL_MAPPER =
            (rs, rowNum) ->
                    new ApprovalRecord(
                            rs.getString("approval_id"),
                            rs.getString("flow_instance_id"),
                            rs.getString("step_id"),
                            rs.getString("approver_role"),
                            ApprovalState.valueOf(rs.getString("state")),
                            rs.getString("approver"),
                            rs.getString("note"),
                            rs.getString("reason"),
                            rs.getBoolean("degraded"),
                            rs.getTimestamp("requested_at").toInstant(),
                            rs.getTimestamp("decided_at") == null
                                    ? null
                                    : rs.getTimestamp("decided_at").toInstant(),
                            rs.getTimestamp("due_at") == null
                                    ? null
                                    : rs.getTimestamp("due_at").toInstant(),
                            ApprovalTimeoutPolicy.valueOf(rs.getString("timeout_policy")));
}
