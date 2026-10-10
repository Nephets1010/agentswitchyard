/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.approval;

import io.github.nephets.agentswitchyard.core.ErrorCode;
import io.github.nephets.agentswitchyard.core.SwitchyardException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 审批门：挂起 / 决策 / 权限 / 意见留痕 / 并发只生效一次。 */
@DisplayName("ApprovalGate（T03：挂起/恢复/角色校验/意见留痕）")
class ApprovalGateTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-10-10T08:00:00Z"), ZoneOffset.UTC);

    private static ApprovalRequest request(String role) {
        return new ApprovalRequest(
                "inst-1",
                "manual-review",
                role,
                "规则评分 0.82 > 阈值 0.75",
                ApprovalTimeoutPolicy.HOLD_FOREVER,
                Duration.ZERO,
                FIXED.instant());
    }

    private static ApprovalGate newGate(ApprovalStore store) {
        return new ApprovalGate(store, new RoleApprovalAuthorizer(), null, FIXED);
    }

    @Test
    @DisplayName("挂起创建 PENDING 记录并可按角色列出")
    void suspendCreatesPending() {
        ApprovalStore store = new InMemoryApprovalStore();
        ApprovalGate gate = newGate(store);

        String approvalId = gate.suspend(request("risk-officer"));

        ApprovalRecord record = store.find(approvalId).orElseThrow();
        assertThat(record.state()).isEqualTo(ApprovalState.PENDING);
        assertThat(record.approverRole()).isEqualTo("risk-officer");
        assertThat(record.reason()).contains("0.82");
        assertThat(record.dueAt()).as("HOLD_FOREVER 永不超时").isNull();
        assertThat(gate.listPending("risk-officer")).hasSize(1);
        assertThat(gate.listPending("credit-officer")).isEmpty();
        assertThat(gate.listPending(null)).hasSize(1);
    }

    @Test
    @DisplayName("决策：通过后状态、审批人、意见完整留痕")
    void decideApprovesAndKeepsNote() {
        ApprovalStore store = new InMemoryApprovalStore();
        ApprovalGate gate = newGate(store);
        String approvalId = gate.suspend(request("risk-officer"));

        ApprovalGate.DecisionResult result =
                gate.decide(
                        approvalId,
                        new ApprovalDecision(
                                "zhang-san", "risk-officer", true, "人工复核通过", false, null));

        assertThat(result.applied()).isTrue();
        assertThat(result.record().state()).isEqualTo(ApprovalState.APPROVED);
        assertThat(result.record().approver()).isEqualTo("zhang-san");
        assertThat(result.record().note()).isEqualTo("人工复核通过");
        assertThat(result.record().decidedAt()).isEqualTo(FIXED.instant());
    }

    @Test
    @DisplayName("★ 并发决策只生效一次：第二个决策 applied=false 且不覆盖第一个")
    void concurrentDecideOnlyOnceEffective() {
        ApprovalStore store = new InMemoryApprovalStore();
        ApprovalGate gate = newGate(store);
        String approvalId = gate.suspend(request("risk-officer"));

        ApprovalGate.DecisionResult first =
                gate.decide(
                        approvalId,
                        new ApprovalDecision("zhang-san", "risk-officer", true, "通过", false, null));
        ApprovalGate.DecisionResult second =
                gate.decide(
                        approvalId,
                        new ApprovalDecision("li-si", "risk-officer", false, "拒绝", false, null));

        assertThat(first.applied()).isTrue();
        assertThat(second.applied()).isFalse();
        ApprovalRecord finalRecord = store.find(approvalId).orElseThrow();
        assertThat(finalRecord.state()).isEqualTo(ApprovalState.APPROVED);
        assertThat(finalRecord.approver()).isEqualTo("zhang-san");
    }

    @Test
    @DisplayName("★ 非授权角色审批抛 SW_APPROVAL_DENIED（T03 验收标准 5）")
    void unauthorizedRoleDenied() {
        ApprovalStore store = new InMemoryApprovalStore();
        ApprovalGate gate = newGate(store);
        String approvalId = gate.suspend(request("risk-officer"));

        assertThatThrownBy(
                        () ->
                                gate.decide(
                                        approvalId,
                                        new ApprovalDecision(
                                                "mallory",
                                                "credit-officer",
                                                true,
                                                "我要放款",
                                                false,
                                                null)))
                .isInstanceOf(SwitchyardException.class)
                .satisfies(
                        ex ->
                                assertThat(((SwitchyardException) ex).errorCode())
                                        .isEqualTo(ErrorCode.SW_APPROVAL_DENIED));
        // 决策未生效，记录仍 PENDING
        assertThat(store.find(approvalId).orElseThrow().state()).isEqualTo(ApprovalState.PENDING);
    }

    @Test
    @DisplayName("花名册校验：角色一致但人不在花名册 → 拒绝")
    void rosterCheck() {
        ApprovalStore store = new InMemoryApprovalStore();
        ApprovalGate gate =
                new ApprovalGate(
                        store,
                        new RoleApprovalAuthorizer(
                                Map.of("risk-officer", java.util.Set.of("zhang-san"))),
                        null,
                        FIXED);
        String approvalId = gate.suspend(request("risk-officer"));

        assertThatThrownBy(
                        () ->
                                gate.decide(
                                        approvalId,
                                        new ApprovalDecision(
                                                "impostor",
                                                "risk-officer",
                                                true,
                                                "x",
                                                false,
                                                null)))
                .isInstanceOf(SwitchyardException.class)
                .hasMessageContaining("花名册");

        assertThat(
                        gate.decide(
                                        approvalId,
                                        new ApprovalDecision(
                                                "zhang-san",
                                                "risk-officer",
                                                true,
                                                "通过",
                                                false,
                                                null))
                                .applied())
                .isTrue();
    }

    @Test
    @DisplayName("决策不存在的审批 → SW_BAD_REQUEST")
    void unknownApprovalRejected() {
        ApprovalGate gate = newGate(new InMemoryApprovalStore());
        assertThatThrownBy(
                        () ->
                                gate.decide(
                                        "ghost",
                                        new ApprovalDecision("a", "b", true, "x", false, null)))
                .isInstanceOf(SwitchyardException.class)
                .satisfies(
                        ex ->
                                assertThat(((SwitchyardException) ex).errorCode())
                                        .isEqualTo(ErrorCode.SW_BAD_REQUEST));
    }

    @Test
    @DisplayName("决策回调只触发一次（CAS 赢家触发）")
    void listenerInvokedOnce() {
        ApprovalStore store = new InMemoryApprovalStore();
        ApprovalGate gate = newGate(store);
        List<Boolean> approvals = new java.util.concurrent.CopyOnWriteArrayList<>();
        gate.setListener((record, approved, degraded) -> approvals.add(approved));
        String approvalId = gate.suspend(request("risk-officer"));

        gate.decide(
                approvalId,
                new ApprovalDecision("zhang-san", "risk-officer", true, "通过", false, null));
        gate.decide(
                approvalId,
                new ApprovalDecision("li-si", "risk-officer", false, "拒绝", false, null));

        assertThat(approvals).containsExactly(true);
    }

    @Test
    @DisplayName("timeoutPolicy 超时到期时间计算（HOLD_FOREVER 为 null）")
    void dueAtComputation() {
        ApprovalRequest hold =
                new ApprovalRequest(
                        "i",
                        "s",
                        "r",
                        "reason",
                        ApprovalTimeoutPolicy.HOLD_FOREVER,
                        Duration.ZERO,
                        FIXED.instant());
        assertThat(hold.dueAt()).isNull();
        ApprovalRequest timed =
                new ApprovalRequest(
                        "i",
                        "s",
                        "r",
                        "reason",
                        ApprovalTimeoutPolicy.AUTO_APPROVE,
                        Duration.ofHours(22),
                        FIXED.instant());
        assertThat(timed.dueAt()).isEqualTo(FIXED.instant().plus(Duration.ofHours(22)));
    }
}
