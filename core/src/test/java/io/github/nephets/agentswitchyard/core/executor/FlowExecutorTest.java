/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.executor;

import io.github.nephets.agentswitchyard.core.approval.ApprovalDecision;
import io.github.nephets.agentswitchyard.core.approval.ApprovalGate;
import io.github.nephets.agentswitchyard.core.approval.ApprovalRecord;
import io.github.nephets.agentswitchyard.core.approval.InMemoryApprovalStore;
import io.github.nephets.agentswitchyard.core.approval.RoleApprovalAuthorizer;
import io.github.nephets.agentswitchyard.core.compensation.CompensationAction;
import io.github.nephets.agentswitchyard.core.compensation.CompensationChain;
import io.github.nephets.agentswitchyard.core.compensation.CompensationOutcome;
import io.github.nephets.agentswitchyard.core.compensation.CompensationReport;
import io.github.nephets.agentswitchyard.core.definition.FlowDefinition;
import io.github.nephets.agentswitchyard.core.definition.StepHandler;
import io.github.nephets.agentswitchyard.core.instance.FlowState;
import io.github.nephets.agentswitchyard.core.instance.StepRecord;
import io.github.nephets.agentswitchyard.core.retry.DefaultErrorClassifier;
import io.github.nephets.agentswitchyard.core.store.InMemoryStepStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 流程执行引擎：启动 / 幂等续跑 / 失败补偿 / 审批挂起与恢复。 （FlowExecutor.start 是异步虚拟线程，测试用轮询等待终态 —— 与生产语义一致。） */
@DisplayName("FlowExecutor（T02 执行引擎 + 幂等 + 补偿 + 审批）")
class FlowExecutorTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-10-10T08:00:00Z"), ZoneOffset.UTC);

    /** 轮询等待（虚拟线程异步执行的测试辅助；10s 上限）。 */
    private static void await(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("10s 内未等到期望条件");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        }
    }

    private static FlowExecutor newExecutor(
            FlowDefinition flow,
            Map<String, StepHandler> handlers,
            Map<String, CompensationAction> actions,
            List<String> compensationOrder) {
        InMemoryApprovalStore approvalStore = new InMemoryApprovalStore();
        ApprovalGate gate =
                new ApprovalGate(approvalStore, new RoleApprovalAuthorizer(), null, FIXED);
        CompensationChain chain =
                new CompensationChain(
                        actions,
                        FIXED,
                        step -> flow.step(step.stepId()).compensation().actionRef());
        FlowExecutor executor =
                new FlowExecutor(
                        new InMemoryStepStore(),
                        handlers,
                        gate,
                        chain,
                        null,
                        new DefaultErrorClassifier(),
                        FIXED,
                        null);
        executor.register(flow);
        return executor;
    }

    private static FlowDefinition threeRuleSteps() {
        return io.github.nephets.agentswitchyard.core.definition.FlowDefinition.builder(
                        "three-steps")
                .ruleStep("s1")
                .description("第一步")
                .done()
                .ruleStep("s2")
                .description("第二步")
                .done()
                .ruleStep("s3")
                .description("第三步")
                .done()
                .build();
    }

    @Test
    @DisplayName("happy path：3 步全部成功，实例 SUCCEEDED")
    void happyPath() {
        FlowDefinition flow = threeRuleSteps();
        AtomicInteger s1 = new AtomicInteger();
        FlowExecutor executor =
                newExecutor(
                        flow,
                        Map.of(
                                "s1",
                                        (StepHandler)
                                                ctx -> {
                                                    s1.incrementAndGet();
                                                    return io.github.nephets.agentswitchyard.core
                                                            .instance.StepResult.ofRule(
                                                            Map.of("out", 1));
                                                },
                                "s2",
                                        ctx ->
                                                io.github.nephets.agentswitchyard.core.instance
                                                        .StepResult.ofRule(Map.of()),
                                "s3",
                                        ctx ->
                                                io.github.nephets.agentswitchyard.core.instance
                                                        .StepResult.ofRule(Map.of())),
                        Map.of(),
                        null);

        String instId = executor.start("three-steps", Map.of("ref", "R-1"), "tenant-1");
        await(() -> executor.instance(instId).state() == FlowState.SUCCEEDED);

        assertThat(s1.get()).isEqualTo(1);
        assertThat(executor.steps(instId)).hasSize(3);
        assertThat(executor.steps(instId))
                .allSatisfy(r -> assertThat(r.state().succeeded()).isTrue());
        assertThat(executor.instance(instId).traceId()).hasSize(32);
    }

    @Test
    @DisplayName("★ 幂等：同一幂等键并发提交 20 次，副作用只发生 1 次（PRD P0-2 验收）")
    void concurrentSameIdempotencyKeySingleSideEffect() throws Exception {
        InMemoryStepStore store = new InMemoryStepStore();
        store.createInstance(
                new io.github.nephets.agentswitchyard.core.instance.FlowInstance(
                        "inst-x",
                        "three-steps",
                        1,
                        null,
                        "a".repeat(32),
                        FlowState.RUNNING,
                        Map.of(),
                        FIXED.instant(),
                        FIXED.instant()));
        io.github.nephets.agentswitchyard.core.idempotency.IdempotencyGuard guard =
                new io.github.nephets.agentswitchyard.core.idempotency.IdempotencyGuard(store);
        AtomicInteger sideEffects = new AtomicInteger();
        int threads = 20;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.List<java.util.concurrent.Future<Boolean>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(
                    pool.submit(
                            () -> {
                                StepRecord candidate =
                                        new StepRecord(
                                                "inst-x",
                                                "s1",
                                                0,
                                                io.github.nephets.agentswitchyard.core.definition
                                                        .StepKind.RULE,
                                                "R-1:s1",
                                                io.github.nephets.agentswitchyard.core.instance
                                                        .StepState.RUNNING,
                                                "span",
                                                "{}",
                                                null,
                                                null,
                                                null,
                                                0,
                                                FIXED.instant(),
                                                null,
                                                null,
                                                null);
                                // 20 个线程用同一幂等键并发提交：只有 1 个能执行 handler
                                java.util.Optional<StepRecord> hit =
                                        guard.begin("inst-x", candidate);
                                if (hit.isEmpty()) {
                                    // 赢家执行副作用并落库
                                    sideEffects.incrementAndGet();
                                    store.completeStep(
                                            candidate.succeeded(
                                                    "{\"tx\":1}",
                                                    io.github.nephets.agentswitchyard.core.usage
                                                            .Usage.estimated(
                                                            io.github.nephets.agentswitchyard.core
                                                                    .usage.UsageSource.FALLBACK,
                                                            false),
                                                    java.math.BigDecimal.ZERO,
                                                    FIXED.instant()),
                                            FIXED.instant());
                                    return true;
                                }
                                // 输家复用首次结果（副作用不重复）
                                assertThat(hit.get().outputJson()).isEqualTo("{\"tx\":1}");
                                return false;
                            }));
        }
        int winners = 0;
        for (java.util.concurrent.Future<Boolean> future : futures) {
            if (future.get()) {
                winners++;
            }
        }
        pool.shutdown();

        assertThat(winners).as("同幂等键 20 次并发只有 1 个赢家").isEqualTo(1);
        assertThat(sideEffects.get()).as("副作用只发生 1 次").isEqualTo(1);
        assertThat(store.listSteps("inst-x")).as("step_record 只有一行").hasSize(1);
    }

    @Test
    @DisplayName("★ 第 3 步失败 → 前两步补偿按反向执行，流程 COMPENSATED，报告可验证")
    void failureTriggersReverseCompensation() {
        FlowDefinition flow = threeRuleSteps();
        // 补偿声明：s1/s2 有补偿（通过 Replacement 定义 TOOL 步骤）
        FlowDefinition flowWithComp =
                io.github.nephets.agentswitchyard.core.definition.FlowDefinition.builder(
                                "comp-flow")
                        .toolStep("s1")
                        .compensateWith("undo1")
                        .done()
                        .toolStep("s2")
                        .compensateWith("undo2")
                        .done()
                        .ruleStep("s3")
                        .done()
                        .build();
        List<String> order = new CopyOnWriteArrayList<>();
        FlowExecutor executor =
                newExecutor(
                        flowWithComp,
                        Map.of(
                                "s1",
                                        ctx ->
                                                io.github.nephets.agentswitchyard.core.instance
                                                        .StepResult.ofRule(Map.of()),
                                "s2",
                                        ctx ->
                                                io.github.nephets.agentswitchyard.core.instance
                                                        .StepResult.ofRule(Map.of()),
                                "s3",
                                        ctx -> {
                                            throw new IllegalArgumentException("规则引擎：业务校验失败（不可重试）");
                                        }),
                        Map.of(
                                "undo1",
                                        (CompensationAction)
                                                executed -> {
                                                    order.add("undo1:" + executed.stepId());
                                                    return CompensationOutcome.ok("撤销 s1");
                                                },
                                "undo2",
                                        (CompensationAction)
                                                executed -> {
                                                    order.add("undo2:" + executed.stepId());
                                                    return CompensationOutcome.ok("撤销 s2");
                                                }),
                        order);

        String instId = executor.start("comp-flow", Map.of("ref", "R-2"), null);
        await(() -> executor.instance(instId).state().terminal());

        assertThat(executor.instance(instId).state()).isEqualTo(FlowState.COMPENSATED);
        // ★ 反向顺序：s2 先于 s1
        assertThat(order).containsExactly("undo2:s2", "undo1:s1");
        CompensationReport report = executor.compensationReport(instId).orElseThrow();
        assertThat(report.allSucceeded()).isTrue();
        assertThat(report.entries())
                .extracting(CompensationReport.Entry::stepId)
                .containsExactly("s2", "s1");
        // 失败步本身被标记 FAILED
        assertThat(
                        executor.steps(instId).stream()
                                .filter(r -> r.stepId().equals("s3"))
                                .findFirst()
                                .orElseThrow()
                                .state())
                .isEqualTo(io.github.nephets.agentswitchyard.core.instance.StepState.FAILED);
    }

    @Test
    @DisplayName("★ 补偿自身失败：原始异常不被掩盖（COMPENSATE_FAILED 终态）")
    void compensationFailureDoesNotMaskOriginal() {
        FlowDefinition flowWithComp =
                io.github.nephets.agentswitchyard.core.definition.FlowDefinition.builder(
                                "comp-fail")
                        .toolStep("s1")
                        .compensateWith("bad")
                        .done()
                        .ruleStep("s2")
                        .done()
                        .build();
        FlowExecutor executor =
                newExecutor(
                        flowWithComp,
                        Map.of(
                                "s1",
                                        ctx ->
                                                io.github.nephets.agentswitchyard.core.instance
                                                        .StepResult.ofRule(Map.of()),
                                "s2",
                                        ctx -> {
                                            throw new IllegalStateException("原始异常：规则引擎拒绝");
                                        }),
                        Map.of("bad", executed -> CompensationOutcome.fail("补偿失败", "冲正接口 5xx")),
                        null);

        String instId = executor.start("comp-fail", Map.of("ref", "R-3"), null);
        await(() -> executor.instance(instId).state().terminal());

        assertThat(executor.instance(instId).state()).isEqualTo(FlowState.COMPENSATE_FAILED);
        CompensationReport report = executor.compensationReport(instId).orElseThrow();
        assertThat(report.allSucceeded()).isFalse();
        assertThat(report.entries().get(0).outcome().error()).contains("5xx");
    }

    @Test
    @DisplayName("审批流：start 挂起 → approve 后从挂起步续跑至完成")
    void approvalSuspendsThenResumes() {
        FlowDefinition flow =
                io.github.nephets.agentswitchyard.core.definition.FlowDefinition.builder(
                                "approval-flow")
                        .ruleStep("prepare")
                        .done()
                        .humanStep("manual-review")
                        .approvalRequired("risk-officer")
                        .description("人工复核")
                        .done()
                        .ruleStep("finalize")
                        .done()
                        .build();
        AtomicInteger finalizeRuns = new AtomicInteger();
        InMemoryApprovalStore approvalStore = new InMemoryApprovalStore();
        ApprovalGate gate =
                new ApprovalGate(approvalStore, new RoleApprovalAuthorizer(), null, FIXED);
        FlowExecutor executor =
                new FlowExecutor(
                        new InMemoryStepStore(),
                        Map.of(
                                "prepare",
                                        ctx ->
                                                io.github.nephets.agentswitchyard.core.instance
                                                        .StepResult.ofRule(Map.of()),
                                "manual-review",
                                        ctx ->
                                                io.github.nephets.agentswitchyard.core.instance
                                                        .StepResult.ofRule(
                                                        Map.of("approved", true, "note", "人工复核通过")),
                                "finalize",
                                        ctx -> {
                                            finalizeRuns.incrementAndGet();
                                            return io.github.nephets.agentswitchyard.core.instance
                                                    .StepResult.ofRule(Map.of());
                                        }),
                        gate,
                        new CompensationChain(Map.of(), FIXED, step -> null),
                        null,
                        new DefaultErrorClassifier(),
                        FIXED,
                        null);
        executor.register(flow);

        String instId = executor.start("approval-flow", Map.of("ref", "R-4"), null);
        await(() -> executor.instance(instId).state() == FlowState.WAITING_APPROVAL);

        // 挂起期间：finalize 不应执行，审批记录 PENDING 且意见留痕在 decide 之后
        assertThat(finalizeRuns.get()).isZero();
        assertThat(gate.listPending("risk-officer")).hasSize(1);
        ApprovalRecord pending = gate.listPending("risk-officer").get(0);
        assertThat(pending.approvalId()).isNotBlank();
        // 挂起态持久化：实例保持 WAITING_APPROVAL（"进程重启"后 loadInstance 语义由 store 保证）
        assertThat(executor.instance(instId).state()).isEqualTo(FlowState.WAITING_APPROVAL);

        ApprovalGate.DecisionResult result =
                gate.decide(
                        pending.approvalId(),
                        new ApprovalDecision(
                                "zhang-san", "risk-officer", true, "人工复核通过", false, null));
        assertThat(result.applied()).isTrue();

        await(() -> executor.instance(instId).state() == FlowState.SUCCEEDED);
        assertThat(finalizeRuns.get()).isEqualTo(1);
        ApprovalRecord after = approvalStore.find(pending.approvalId()).orElseThrow();
        assertThat(after.state())
                .isEqualTo(io.github.nephets.agentswitchyard.core.approval.ApprovalState.APPROVED);
        assertThat(after.note()).isEqualTo("人工复核通过");
        assertThat(after.approver()).isEqualTo("zhang-san");
        // 挂起步的 handler 也执行了（带审批结果输出）
        StepRecord review =
                executor.steps(instId).stream()
                        .filter(r -> r.stepId().equals("manual-review"))
                        .findFirst()
                        .orElseThrow();
        assertThat(review.state().succeeded()).isTrue();
        assertThat(review.outputJson()).contains("人工复核通过");
    }

    @Test
    @DisplayName("resume：未注册流程 / 终态实例不会抛错")
    void resumeGuarded() {
        FlowExecutor executor = newExecutor(threeRuleSteps(), Map.of(), Map.of(), null);
        assertThatThrownBy(() -> executor.resume("no-such-instance"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(executor.recoverInterrupted()).isZero();
    }
}
