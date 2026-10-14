/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.approval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 四种超时策略逐一断言（T03 验收标准 4）。 ★ 用注入的虚拟时钟推进时间，禁用 Thread.sleep。 */
@DisplayName("ApprovalTimeoutPolicy（四种策略 + 虚拟时钟）")
class ApprovalTimeoutPolicyTest {

    private static final Instant T0 = Instant.parse("2026-10-10T08:00:00Z");

    /** 可手动推进的虚拟时钟。 */
    private static final class MutableClock extends Clock {

        private Instant now = T0;

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private ApprovalRecord suspend(
            MutableClock clock,
            ApprovalStore store,
            ApprovalGate gate,
            ApprovalTimeoutPolicy policy) {
        return store.find(
                        gate.suspend(
                                new ApprovalRequest(
                                        "inst-1",
                                        "manual-review",
                                        "risk-officer",
                                        "评分超标",
                                        policy,
                                        Duration.ofHours(22),
                                        clock.instant())))
                .orElseThrow();
    }

    @Test
    @DisplayName("AUTO_APPROVE：到期后自动通过，审批人记为 system-timeout")
    void autoApprove() {
        MutableClock clock = new MutableClock();
        ApprovalStore store = new InMemoryApprovalStore();
        ApprovalGate gate = new ApprovalGate(store, new RoleApprovalAuthorizer(), null, clock);
        ApprovalRecord record = suspend(clock, store, gate, ApprovalTimeoutPolicy.AUTO_APPROVE);

        clock.advance(Duration.ofHours(23));
        ApprovalRecord after = gate.applyTimeoutPolicy(record.approvalId());

        assertThat(after.state()).isEqualTo(ApprovalState.TIMEOUT);
        assertThat(after.approver()).isEqualTo("system-timeout");
        assertThat(after.note()).contains("自动通过");
        assertThat(after.degraded()).isFalse();
        // 未到期时不动作
        assertThat(gate.applyTimeoutPolicy(record.approvalId()).state())
                .isEqualTo(ApprovalState.TIMEOUT);
    }

    @Test
    @DisplayName("AUTO_REJECT：到期后超时记录 + 回调 approved=false（流程进入补偿）")
    void autoReject() {
        MutableClock clock = new MutableClock();
        ApprovalStore store = new InMemoryApprovalStore();
        List<Boolean> decisions = new java.util.concurrent.CopyOnWriteArrayList<>();
        ApprovalGate gate = new ApprovalGate(store, new RoleApprovalAuthorizer(), null, clock);
        gate.setListener((record, approved, degraded) -> decisions.add(approved));
        ApprovalRecord record = suspend(clock, store, gate, ApprovalTimeoutPolicy.AUTO_REJECT);

        clock.advance(Duration.ofHours(23));
        ApprovalRecord after = gate.applyTimeoutPolicy(record.approvalId());

        assertThat(after.state()).isEqualTo(ApprovalState.TIMEOUT);
        assertThat(after.note()).contains("自动拒绝");
        assertThat(decisions).containsExactly(false);
    }

    @Test
    @DisplayName("AUTO_DEGRADE：到期后降级通过（degraded=true）")
    void autoDegrade() {
        MutableClock clock = new MutableClock();
        ApprovalStore store = new InMemoryApprovalStore();
        List<Boolean> degradedFlags = new java.util.concurrent.CopyOnWriteArrayList<>();
        ApprovalGate gate = new ApprovalGate(store, new RoleApprovalAuthorizer(), null, clock);
        gate.setListener((record, approved, degraded) -> degradedFlags.add(degraded));
        ApprovalRecord record = suspend(clock, store, gate, ApprovalTimeoutPolicy.AUTO_DEGRADE);

        clock.advance(Duration.ofHours(23));
        ApprovalRecord after = gate.applyTimeoutPolicy(record.approvalId());

        assertThat(after.state()).isEqualTo(ApprovalState.TIMEOUT);
        assertThat(after.degraded()).isTrue();
        assertThat(degradedFlags).containsExactly(true);
    }

    @Test
    @DisplayName("HOLD_FOREVER：即使时间推进一年也不做任何决策")
    void holdForever() {
        MutableClock clock = new MutableClock();
        ApprovalStore store = new InMemoryApprovalStore();
        ApprovalGate gate = new ApprovalGate(store, new RoleApprovalAuthorizer(), null, clock);
        ApprovalRecord record = suspend(clock, store, gate, ApprovalTimeoutPolicy.HOLD_FOREVER);

        clock.advance(Duration.ofDays(365));
        ApprovalRecord after = gate.applyTimeoutPolicy(record.approvalId());

        assertThat(after.state()).isEqualTo(ApprovalState.PENDING);
    }

    @Test
    @DisplayName("未到期 / 已决策的记录不受超时扫描影响")
    void notDueOrDecidedUntouched() {
        MutableClock clock = new MutableClock();
        ApprovalStore store = new InMemoryApprovalStore();
        ApprovalGate gate = new ApprovalGate(store, new RoleApprovalAuthorizer(), null, clock);
        ApprovalRecord record = suspend(clock, store, gate, ApprovalTimeoutPolicy.AUTO_APPROVE);

        // 未到期
        ApprovalRecord untouched = gate.applyTimeoutPolicy(record.approvalId());
        assertThat(untouched.state()).isEqualTo(ApprovalState.PENDING);

        // 已人工决策
        gate.decide(
                record.approvalId(),
                new ApprovalDecision("zhang-san", "risk-officer", true, "人工通过", false, null));
        clock.advance(Duration.ofDays(2));
        assertThat(gate.applyTimeoutPolicy(record.approvalId()).state())
                .isEqualTo(ApprovalState.APPROVED);
    }
}
