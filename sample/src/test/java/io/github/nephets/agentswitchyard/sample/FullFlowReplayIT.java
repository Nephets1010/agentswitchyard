/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.sample;

import io.github.nephets.agentswitchyard.core.approval.ApprovalDecision;
import io.github.nephets.agentswitchyard.core.approval.ApprovalGate;
import io.github.nephets.agentswitchyard.core.approval.ApprovalRecord;
import io.github.nephets.agentswitchyard.core.executor.FlowExecutor;
import io.github.nephets.agentswitchyard.core.instance.FlowState;
import io.github.nephets.agentswitchyard.core.replay.TraceDiff;
import io.github.nephets.agentswitchyard.sample.ledger.Ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ 确定性回放端到端：真实执行（含 LLM 步骤与人工审批）→ 回放 → identical=true。
 *
 * <p>回放跑在隔离 InMemoryStepStore 上：放款 handler 收到 replay=true 只重算输出， 副作用台账<b>不新增行</b>；LLM
 * 响应来自录制注入（ADR-0004）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("FullFlowReplayIT（确定性回放）")
class FullFlowReplayIT {

    @Autowired private FlowExecutor flowExecutor;

    @Autowired private ApprovalGate approvalGate;

    @Autowired private Ledger ledger;

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("20s 内未等到期望条件");
            }
            Thread.sleep(20);
        }
    }

    @Test
    @DisplayName("完成一笔审批后回放：identical=true 且不产生新副作用")
    void replayIsIdenticalAndSideEffectFree() throws Exception {
        String instanceId =
                flowExecutor.start(
                        "credit-approve", Map.of("ref", "R-REPLAY-1", "amount", 300_000), null);
        await(() -> flowExecutor.instance(instanceId).state() == FlowState.WAITING_APPROVAL);

        // 引擎门决策（HTTP 层已在 FullFlowApprovalIT 验证）
        ApprovalRecord pending =
                approvalGate.listPending("risk-officer").stream()
                        .filter(r -> r.flowInstanceId().equals(instanceId))
                        .findFirst()
                        .orElseThrow();
        assertThat(
                        approvalGate
                                .decide(
                                        pending.approvalId(),
                                        new ApprovalDecision(
                                                "li-si",
                                                "risk-officer",
                                                true,
                                                "回放用例审批",
                                                false,
                                                null))
                                .applied())
                .isTrue();
        await(() -> flowExecutor.instance(instanceId).state() == FlowState.SUCCEEDED);

        int ledgerRowsBefore = ledger.rows(instanceId).size();
        assertThat(ledgerRowsBefore).isEqualTo(1);

        // ★ 回放：录制的响应注入重跑，差异为空
        TraceDiff diff = flowExecutor.replay(instanceId);
        assertThat(diff.identical()).as("回放应与历史执行完全一致：" + diff.diffs()).isTrue();

        // 副作用台账不新增行（回放零真实副作用）
        assertThat(ledger.rows(instanceId)).hasSize(ledgerRowsBefore);
    }
}
