/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.store;

import io.github.nephets.agentswitchyard.core.definition.StepKind;
import io.github.nephets.agentswitchyard.core.instance.FlowInstance;
import io.github.nephets.agentswitchyard.core.instance.FlowState;
import io.github.nephets.agentswitchyard.core.instance.StepRecord;
import io.github.nephets.agentswitchyard.core.instance.StepState;
import io.github.nephets.agentswitchyard.core.store.StepStore;
import io.github.nephets.agentswitchyard.store.support.IdempotentInsert;
import io.github.nephets.agentswitchyard.store.support.StoreCodec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * {@link StepStore} 的 JDBC 实现（H2 / MySQL / PostgreSQL / 达梦四方言）。
 *
 * <p>★ 幂等键与步骤记录<b>同表同事务</b>：{@code tryBeginStep} 就是对 {@code step_record} 的一条方言化幂等 INSERT（冲突键 {@code
 * (flow_instance_id, step_id, idem_key)}，唯一索引见迁移 V2）。 不存在「先查幂等表、再执行、再写步骤表」的跨表竞态 —— 插入本身就是判定。
 *
 * <p>契约对齐（core {@link StepStore} javadoc）：插入成功时返回<b>传入的 candidate 对象本身</b>， 调用方以引用相等判定「赢得执行权」。
 */
public final class JdbcStepStore implements StepStore {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcStepStore.class);

    private static final String TABLE = "step_record";
    private static final List<String> STEP_COLUMNS =
            List.of(
                    "flow_instance_id",
                    "step_id",
                    "seq",
                    "kind",
                    "idem_key",
                    "state",
                    "span_id",
                    "input_json",
                    "output_json",
                    "usage_json",
                    "cost_cny",
                    "attempts",
                    "started_at",
                    "ended_at",
                    "error_message",
                    "approval_id");
    private static final List<String> STEP_CONFLICT_KEYS =
            List.of("flow_instance_id", "step_id", "idem_key");

    private static final String INSTANCE_TABLE = "flow_instance";
    private static final List<String> INSTANCE_COLUMNS =
            List.of(
                    "flow_instance_id",
                    "flow_id",
                    "flow_version",
                    "tenant_id",
                    "trace_id",
                    "state",
                    "input_json",
                    "started_at",
                    "updated_at");

    private final JdbcTemplate jdbc;
    private final IdempotentInsert beginStepInsert;
    private final IdempotentInsert createInstanceInsert;

    /**
     * 创建 JDBC 步骤存储。
     *
     * @param jdbc Spring {@link JdbcTemplate}（由调用方配置数据源与连接池）
     * @param dialect 目标数据库方言
     */
    public JdbcStepStore(JdbcTemplate jdbc, Dialect dialect) {
        this.jdbc = jdbc;
        this.beginStepInsert =
                IdempotentInsert.of(dialect, TABLE, STEP_COLUMNS, STEP_CONFLICT_KEYS);
        this.createInstanceInsert =
                IdempotentInsert.of(
                        dialect, INSTANCE_TABLE, INSTANCE_COLUMNS, List.of("flow_instance_id"));
    }

    // ==================== 实例 ====================

    @Override
    public void createInstance(FlowInstance instance) {
        // 实例表幂等创建（方言化）：同 ID 重复创建静默保留首行。
        // 注意用 ArrayList：tenantId/traceId/input_json 允许为 null，List.of 不接受 null 元素。
        List<Object> values = new ArrayList<>();
        values.add(instance.flowInstanceId());
        values.add(instance.flowId());
        values.add(instance.flowVersion());
        values.add(instance.tenantId());
        values.add(instance.traceId());
        values.add(instance.state().name());
        values.add(
                instance.input().isEmpty()
                        ? null
                        : io.github.nephets.agentswitchyard.core.Json.write(instance.input()));
        values.add(Timestamp.from(instance.startedAt()));
        values.add(Timestamp.from(instance.updatedAt()));
        jdbc.update(createInstanceInsert.sql(), bindWithConflictLeading(values).toArray());
    }

    /** 幂等插入的参数绑定：达梦 MERGE 形态下冲突键参数在前（USING 子句），其余方言按列序。 */
    private List<Object> bindWithConflictLeading(List<Object> values) {
        if (createInstanceInsert.leadingParams() > 0) {
            // flow_instance 的冲突键是 flow_instance_id（列 0）
            List<Object> merged = new ArrayList<>();
            merged.add(values.get(0));
            merged.addAll(values);
            return merged;
        }
        return values;
    }

    @Override
    public FlowInstance loadInstance(String flowInstanceId) {
        List<FlowInstance> rows =
                jdbc.query(
                        "SELECT flow_instance_id, flow_id, flow_version, tenant_id, trace_id, state,"
                                + " input_json, started_at, updated_at FROM flow_instance WHERE flow_instance_id = ?",
                        INSTANCE_MAPPER,
                        flowInstanceId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("流程实例不存在: " + flowInstanceId);
        }
        return rows.get(0);
    }

    @Override
    public boolean compareAndSetInstanceState(
            String flowInstanceId, FlowState expected, FlowState target, Instant at) {
        FlowInstance current = loadInstance(flowInstanceId);
        if (current.state() != expected) {
            return false;
        }
        expected.transitionTo(target); // 非法转移 fail-fast（与内存实现同语义）
        return jdbc.update(
                        "UPDATE flow_instance SET state = ?, updated_at = ?"
                                + " WHERE flow_instance_id = ? AND state = ?",
                        target.name(),
                        Timestamp.from(at),
                        flowInstanceId,
                        expected.name())
                == 1;
    }

    @Override
    public void updateInstanceState(String flowInstanceId, FlowState target, Instant at) {
        FlowInstance current = loadInstance(flowInstanceId);
        current.state().transitionTo(target);
        jdbc.update(
                "UPDATE flow_instance SET state = ?, updated_at = ? WHERE flow_instance_id = ?",
                target.name(),
                Timestamp.from(at),
                flowInstanceId);
    }

    @Override
    public List<FlowInstance> listInstancesInState(FlowState state) {
        return jdbc.query(
                "SELECT flow_instance_id, flow_id, flow_version, tenant_id, trace_id, state, input_json,"
                        + " started_at, updated_at FROM flow_instance WHERE state = ? ORDER BY started_at",
                INSTANCE_MAPPER,
                state.name());
    }

    // ==================== 步骤（幂等判定的核心） ====================

    @Override
    public StepRecord tryBeginStep(StepRecord candidate) {
        List<Object> values = stepValues(candidate);
        Object[] params;
        if (beginStepInsert.leadingParams() > 0) {
            // 达梦 MERGE：冲突键参数在前（USING 子句），插入列参数在后
            List<Object> merged = new ArrayList<>(beginStepInsert.leadingParams());
            for (String key : STEP_CONFLICT_KEYS) {
                merged.add(values.get(STEP_COLUMNS.indexOf(key)));
            }
            merged.addAll(values);
            params = merged.toArray();
        } else {
            params = values.toArray();
        }
        int inserted;
        try {
            inserted = jdbc.update(beginStepInsert.sql(), params);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            // H2 的 MERGE 在并发窗口下两个事务都判定 NOT MATCHED，后提交者撞唯一索引 ——
            // 这正是「有并发赢家」的信号：本次调用是输家，回落到读取既有记录
            LOG.debug(
                    "幂等插入冲突，本调用为输家 instId={} stepId={}（回落到读取既有记录）",
                    candidate.flowInstanceId(),
                    candidate.stepId());
            inserted = 0;
        }
        if (inserted == 1) {
            return candidate; // ★ 插入成功：返回 candidate 本身（契约：引用相等 = 赢得执行权）
        }
        return findExistingAfterConflict(candidate);
    }

    /** 冲突后读取既有记录（短暂重试：并发赢家的行可能还未对本事务可见）。 */
    private StepRecord findExistingAfterConflict(StepRecord candidate) {
        IllegalStateException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            Optional<StepRecord> existing =
                    findStep(candidate.flowInstanceId(), candidate.stepId());
            if (existing.isPresent()) {
                return existing.get();
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("幂等冲突后读取既有记录被中断", ex);
            }
            last =
                    new IllegalStateException(
                            "幂等插入冲突但查不到既有记录: "
                                    + candidate.flowInstanceId()
                                    + "/"
                                    + candidate.stepId());
            LOG.warn(
                    "幂等插入冲突后读取既有记录失败（第 {}/5 次）instId={} stepId={}",
                    attempt + 1,
                    candidate.flowInstanceId(),
                    candidate.stepId());
        }
        throw last;
    }

    @Override
    public void completeStep(StepRecord record, Instant at) {
        jdbc.update(
                "UPDATE step_record SET state = 'SUCCEEDED', output_json = ?, usage_json = ?, cost_cny = ?,"
                        + " attempts = ?, ended_at = ? WHERE flow_instance_id = ? AND step_id = ?",
                record.outputJson(),
                StoreCodec.writeUsage(record.usage()),
                record.costCny(),
                record.attempts(),
                Timestamp.from(at),
                record.flowInstanceId(),
                record.stepId());
    }

    @Override
    public void markWaitingApproval(
            String flowInstanceId, String stepId, String approvalId, Instant at) {
        jdbc.update(
                "UPDATE step_record SET state = 'WAITING_APPROVAL', approval_id = ?, ended_at = ?"
                        + " WHERE flow_instance_id = ? AND step_id = ?",
                approvalId,
                Timestamp.from(at),
                flowInstanceId,
                stepId);
    }

    @Override
    public void markFailed(String flowInstanceId, String stepId, String reason, Instant at) {
        jdbc.update(
                "UPDATE step_record SET state = 'FAILED', error_message = ?, ended_at = ?"
                        + " WHERE flow_instance_id = ? AND step_id = ?",
                reason,
                Timestamp.from(at),
                flowInstanceId,
                stepId);
    }

    @Override
    public void markCompensated(String flowInstanceId, String stepId, boolean success, Instant at) {
        jdbc.update(
                "UPDATE step_record SET state = ?, ended_at = ? WHERE flow_instance_id = ? AND step_id = ?",
                (success ? StepState.COMPENSATED : StepState.COMPENSATE_FAILED).name(),
                Timestamp.from(at),
                flowInstanceId,
                stepId);
    }

    @Override
    public void failInterruptedSteps(String flowInstanceId, Instant at) {
        jdbc.update(
                "UPDATE step_record SET state = 'FAILED', error_message = '进程中断（崩溃恢复）', ended_at = ?"
                        + " WHERE flow_instance_id = ? AND state = 'RUNNING'",
                Timestamp.from(at),
                flowInstanceId);
    }

    @Override
    public void markStepResumed(String flowInstanceId, String stepId, Instant at) {
        jdbc.update(
                "UPDATE step_record SET state = 'RUNNING' WHERE flow_instance_id = ? AND step_id = ?",
                flowInstanceId,
                stepId);
    }

    @Override
    public Optional<StepRecord> findStep(String flowInstanceId, String stepId) {
        List<StepRecord> rows =
                jdbc.query(
                        stepSelectSql() + " WHERE flow_instance_id = ? AND step_id = ?",
                        STEP_MAPPER,
                        flowInstanceId,
                        stepId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<StepRecord> listSteps(String flowInstanceId) {
        List<StepRecord> rows =
                jdbc.query(
                        stepSelectSql() + " WHERE flow_instance_id = ?",
                        STEP_MAPPER,
                        flowInstanceId);
        rows.sort(Comparator.comparingInt(StepRecord::seq));
        return rows;
    }

    @Override
    public List<StepRecord> listCompletedSteps(String flowInstanceId) {
        return listSteps(flowInstanceId).stream().filter(r -> r.state().succeeded()).toList();
    }

    private String stepSelectSql() {
        return "SELECT flow_instance_id, step_id, seq, kind, idem_key, state, span_id, input_json,"
                + " output_json, usage_json, cost_cny, attempts, started_at, ended_at, error_message,"
                + " approval_id FROM step_record";
    }

    /** INSERT 参数（与 STEP_COLUMNS 顺序一致）。 */
    private List<Object> stepValues(StepRecord r) {
        List<Object> values = new ArrayList<>(STEP_COLUMNS.size());
        values.add(r.flowInstanceId());
        values.add(r.stepId());
        values.add(r.seq());
        values.add(r.kind().name());
        values.add(r.idemKey());
        values.add(r.state().name());
        values.add(r.spanId());
        values.add(r.inputJson());
        values.add(r.outputJson());
        values.add(StoreCodec.writeUsage(r.usage()));
        values.add(r.costCny());
        values.add(r.attempts());
        values.add(StoreCodec.toTimestamp(r.startedAt()));
        values.add(StoreCodec.toTimestamp(r.endedAt()));
        values.add(r.errorMessage());
        values.add(r.approvalId());
        return values;
    }

    private static final RowMapper<StepRecord> STEP_MAPPER =
            (rs, rowNum) ->
                    new StepRecord(
                            rs.getString("flow_instance_id"),
                            rs.getString("step_id"),
                            rs.getInt("seq"),
                            StepKind.valueOf(rs.getString("kind")),
                            rs.getString("idem_key"),
                            StepState.valueOf(rs.getString("state")),
                            rs.getString("span_id"),
                            rs.getString("input_json"),
                            rs.getString("output_json"),
                            StoreCodec.readUsage(rs.getString("usage_json")),
                            rs.getBigDecimal("cost_cny") == null
                                    ? BigDecimal.ZERO
                                    : rs.getBigDecimal("cost_cny"),
                            rs.getInt("attempts"),
                            StoreCodec.toInstant(rs.getTimestamp("started_at")),
                            StoreCodec.toInstant(rs.getTimestamp("ended_at")),
                            rs.getString("error_message"),
                            rs.getString("approval_id"));

    private static final RowMapper<FlowInstance> INSTANCE_MAPPER =
            (rs, rowNum) ->
                    new FlowInstance(
                            rs.getString("flow_instance_id"),
                            rs.getString("flow_id"),
                            rs.getInt("flow_version"),
                            rs.getString("tenant_id"),
                            rs.getString("trace_id"),
                            FlowState.valueOf(rs.getString("state")),
                            StoreCodec.readInputMap(rs.getString("input_json")),
                            StoreCodec.toInstant(rs.getTimestamp("started_at")),
                            StoreCodec.toInstant(rs.getTimestamp("updated_at")));
}
