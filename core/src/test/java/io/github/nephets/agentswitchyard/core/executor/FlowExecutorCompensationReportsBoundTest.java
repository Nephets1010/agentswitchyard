/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.executor;

import io.github.nephets.agentswitchyard.core.approval.ApprovalGate;
import io.github.nephets.agentswitchyard.core.approval.InMemoryApprovalStore;
import io.github.nephets.agentswitchyard.core.approval.RoleApprovalAuthorizer;
import io.github.nephets.agentswitchyard.core.compensation.CompensationAction;
import io.github.nephets.agentswitchyard.core.compensation.CompensationChain;
import io.github.nephets.agentswitchyard.core.compensation.CompensationOutcome;
import io.github.nephets.agentswitchyard.core.definition.FlowDefinition;
import io.github.nephets.agentswitchyard.core.definition.StepHandler;
import io.github.nephets.agentswitchyard.core.instance.FlowState;
import io.github.nephets.agentswitchyard.core.retry.DefaultErrorClassifier;
import io.github.nephets.agentswitchyard.core.store.InMemoryStepStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 补偿报告缓存的有界性（P4「长生命周期集合必须有界」）。
 *
 * <p>key = flowInstanceId 只增不减，超限按访问顺序 LRU 淘汰，且淘汰必须可观测（evictedCount）。
 */
@DisplayName("FlowExecutor 补偿报告缓存有界性")
class FlowExecutorCompensationReportsBoundTest {

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

    /** 两步流程：s1 成功（带补偿），s2 恒失败 → 必走 compensateAndClose 产生报告。 */
    private static FlowDefinition failingFlow(String flowId) {
        return FlowDefinition.builder(flowId)
                .toolStep("s1")
                .compensateWith("undo1")
                .done()
                .ruleStep("s2")
                .done()
                .build();
    }

    private static FlowExecutor newExecutor(FlowDefinition flow) {
        ApprovalGate gate =
                new ApprovalGate(
                        new InMemoryApprovalStore(), new RoleApprovalAuthorizer(), null, FIXED);
        FlowExecutor executor =
                new FlowExecutor(
                        new InMemoryStepStore(),
                        Map.of(
                                "s1",
                                (StepHandler)
                                        ctx ->
                                                io.github.nephets.agentswitchyard.core.instance
                                                        .StepResult.ofRule(Map.of()),
                                "s2",
                                (StepHandler)
                                        ctx -> {
                                            throw new IllegalStateException("规则引擎：恒失败");
                                        }),
                        gate,
                        new CompensationChain(
                                Map.of(
                                        "undo1",
                                        (CompensationAction)
                                                executed -> CompensationOutcome.ok("撤销 s1")),
                                FIXED,
                                step -> flow.step(step.stepId()).compensation().actionRef()),
                        null,
                        new DefaultErrorClassifier(),
                        FIXED,
                        null);
        executor.register(flow);
        return executor;
    }

    /** 启动并等待进入终态，返回实例 ID。 */
    private static String startAndAwaitTerminal(FlowExecutor executor, String flowId) {
        String instId = executor.start(flowId, Map.of("ref", "R-bound"), null);
        await(() -> executor.instance(instId).state().terminal());
        return instId;
    }

    @Test
    @DisplayName("默认容量下补偿报告不淘汰、查询可用")
    void defaultCapacityKeepsReports() {
        FlowExecutor executor = newExecutor(failingFlow("f-default"));

        String instId = startAndAwaitTerminal(executor, "f-default");

        assertThat(executor.instance(instId).state()).isEqualTo(FlowState.COMPENSATED);
        assertThat(executor.compensationReport(instId)).isPresent();
        assertThat(executor.compensationReportEvictions()).isZero();
    }

    @Test
    @DisplayName("容量压缩后淘汰最久未访问的报告，且淘汰可观测（evictedCount）")
    void compressedCapacityEvictsObservably() {
        FlowExecutor executor = newExecutor(failingFlow("f-bound"));
        executor.setCompensationReportCapacityForTest(2);

        String first = startAndAwaitTerminal(executor, "f-bound");
        String second = startAndAwaitTerminal(executor, "f-bound");
        String third = startAndAwaitTerminal(executor, "f-bound");

        // 第 3 个报告触发淘汰：最久未访问的 first 被挤掉
        assertThat(executor.compensationReportEvictions()).isEqualTo(1);
        assertThat(executor.compensationReport(first)).isEmpty();
        assertThat(executor.compensationReport(second)).isPresent();
        assertThat(executor.compensationReport(third)).isPresent();
        // 数据本体仍在存储里（淘汰只是查询缓存降级，不是丢证据）
        assertThat(executor.instance(first).state()).isEqualTo(FlowState.COMPENSATED);
    }

    @Test
    @DisplayName("非正压缩容量被拒（测试钩子的 fail-fast）")
    void rejectsNonPositiveCapacity() {
        FlowExecutor executor = newExecutor(failingFlow("f-invalid"));
        assertThatThrownBy(() -> executor.setCompensationReportCapacityForTest(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> executor.setCompensationReportCapacityForTest(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
