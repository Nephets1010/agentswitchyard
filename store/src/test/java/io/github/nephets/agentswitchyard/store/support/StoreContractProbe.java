/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.store.support;

import io.github.nephets.agentswitchyard.core.approval.ApprovalRecord;
import io.github.nephets.agentswitchyard.core.approval.ApprovalState;
import io.github.nephets.agentswitchyard.core.approval.ApprovalTimeoutPolicy;
import io.github.nephets.agentswitchyard.core.definition.StepKind;
import io.github.nephets.agentswitchyard.core.idempotency.IdempotencyGuard;
import io.github.nephets.agentswitchyard.core.instance.FlowInstance;
import io.github.nephets.agentswitchyard.core.instance.FlowState;
import io.github.nephets.agentswitchyard.core.instance.StepRecord;
import io.github.nephets.agentswitchyard.core.instance.StepState;
import io.github.nephets.agentswitchyard.core.usage.Usage;
import io.github.nephets.agentswitchyard.core.usage.UsageSource;
import io.github.nephets.agentswitchyard.store.Dialect;
import io.github.nephets.agentswitchyard.store.JdbcApprovalStore;
import io.github.nephets.agentswitchyard.store.JdbcStepStore;

import org.assertj.core.api.Assertions;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 存储契约探针：MySQL / PostgreSQL 两个 Testcontainers IT 共用的完整契约场景 （避免同一场景在两个 IT 里重复实现 —— 架构 §8 一致性要求）。 */
public final class StoreContractProbe {

    private StoreContractProbe() {
        // 工具类不允许实例化
    }

    /** 标准测试实例。 */
    public static FlowInstance instance(String instanceId) {
        return new FlowInstance(
                instanceId,
                "probe-flow",
                1,
                "tenant-probe",
                "a".repeat(32),
                FlowState.PENDING,
                Map.of("ref", "P-1"),
                Instant.parse("2026-10-10T08:00:00Z"),
                Instant.parse("2026-10-10T08:00:00Z"));
    }

    /** 标准测试步骤记录（RUNNING 起始）。 */
    public static StepRecord stepRecord(String instanceId, String stepId, String idemKey) {
        return new StepRecord(
                instanceId,
                stepId,
                0,
                StepKind.RULE,
                idemKey,
                StepState.RUNNING,
                "span-" + stepId,
                "{\"ref\":\"P-1\"}",
                null,
                Usage.estimated(UsageSource.FALLBACK, false),
                BigDecimal.ZERO,
                0,
                Instant.parse("2026-10-10T08:00:00Z"),
                null,
                null,
                null);
    }

    /** 标准测试审批记录（PENDING）。 */
    public static ApprovalRecord approval(String approvalId, String instanceId) {
        return new ApprovalRecord(
                approvalId,
                instanceId,
                "manual-review",
                "risk-officer",
                ApprovalState.PENDING,
                null,
                null,
                "人工复核",
                false,
                Instant.parse("2026-10-10T08:00:00Z"),
                null,
                null,
                ApprovalTimeoutPolicy.HOLD_FOREVER);
    }

    /**
     * 执行完整存储契约场景（幂等并发单副作用 + 状态机 CAS + 崩溃恢复 + 审批 CAS）。
     *
     * @param jdbc 目标库 JDBC 模板
     * @param dialect 目标库方言
     * @throws Exception 并发执行异常（测试应失败）
     */
    public static void verifyAll(JdbcTemplate jdbc, Dialect dialect) throws Exception {
        JdbcStepStore stepStore = new JdbcStepStore(jdbc, dialect);
        JdbcApprovalStore approvalStore = new JdbcApprovalStore(jdbc, dialect);

        // 1. 实例创建幂等（同 ID 重复创建保留首行）
        stepStore.createInstance(instance("inst-probe"));
        stepStore.createInstance(instance("inst-probe"));
        Assertions.assertThat(stepStore.loadInstance("inst-probe").state())
                .isEqualTo(FlowState.PENDING);
        Assertions.assertThat(stepStore.listInstancesInState(FlowState.PENDING)).hasSize(1);

        // 2. ★ 并发同幂等键：10 线程只有 1 个赢家、副作用只发生 1 次（插入即判定）
        IdempotencyGuard guard = new IdempotencyGuard(stepStore);
        AtomicInteger sideEffects = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final StepRecord candidate = stepRecord("inst-probe", "s1", "P-1:s1");
            futures.add(
                    pool.submit(
                            () -> {
                                if (guard.begin("inst-probe", candidate).isEmpty()) {
                                    sideEffects.incrementAndGet();
                                    stepStore.completeStep(
                                            candidate.succeeded(
                                                    "{\"tx\":1}",
                                                    Usage.estimated(UsageSource.FALLBACK, false),
                                                    BigDecimal.ZERO,
                                                    Instant.now()),
                                            Instant.now());
                                    return true;
                                }
                                return false;
                            }));
        }
        int winners = 0;
        for (Future<Boolean> future : futures) {
            if (future.get()) {
                winners++;
            }
        }
        pool.shutdown();
        Assertions.assertThat(winners).as("并发同幂等键只有 1 个赢家").isEqualTo(1);
        Assertions.assertThat(sideEffects.get()).as("副作用只发生 1 次").isEqualTo(1);
        Assertions.assertThat(stepStore.findStep("inst-probe", "s1").orElseThrow().outputJson())
                .isEqualTo("{\"tx\":1}");
        Assertions.assertThat(stepStore.listCompletedSteps("inst-probe")).hasSize(1);

        // 3. 实例状态 CAS
        Assertions.assertThat(
                        stepStore.compareAndSetInstanceState(
                                "inst-probe", FlowState.PENDING, FlowState.RUNNING, Instant.now()))
                .isTrue();
        Assertions.assertThat(
                        stepStore.compareAndSetInstanceState(
                                "inst-probe", FlowState.PENDING, FlowState.RUNNING, Instant.now()))
                .isFalse();

        // 4. 崩溃恢复前置：RUNNING 残留 → FAILED（幂等守卫不再等待死去的赢家）
        stepStore.tryBeginStep(stepRecord("inst-probe", "s2", "P-1:s2"));
        stepStore.failInterruptedSteps("inst-probe", Instant.now());
        Assertions.assertThat(stepStore.findStep("inst-probe", "s2").orElseThrow().state())
                .isEqualTo(StepState.FAILED);

        // 5. ★ 审批 CAS：并发决策只生效一次
        approvalStore.save(approval("appr-probe-1", "inst-probe"));
        AtomicInteger decided = new AtomicInteger();
        ExecutorService approvalPool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 8; i++) {
            approvalPool.submit(
                    () -> {
                        if (approvalStore.compareAndDecide(
                                "appr-probe-1",
                                ApprovalState.PENDING,
                                ApprovalState.APPROVED,
                                "zhang-san",
                                "同意",
                                false,
                                Instant.now())) {
                            decided.incrementAndGet();
                        }
                    });
        }
        approvalPool.shutdown();
        Assertions.assertThat(approvalPool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        Assertions.assertThat(decided.get()).as("并发审批只生效一次").isEqualTo(1);
        ApprovalRecord after = approvalStore.find("appr-probe-1").orElseThrow();
        Assertions.assertThat(after.state()).isEqualTo(ApprovalState.APPROVED);
        Assertions.assertThat(after.approver()).isEqualTo("zhang-san");
        Assertions.assertThat(after.note()).isEqualTo("同意");
        Assertions.assertThat(approvalStore.listPending("risk-officer")).isEmpty();
        Assertions.assertThat(approvalStore.listByInstance("inst-probe")).hasSize(1);
    }
}
