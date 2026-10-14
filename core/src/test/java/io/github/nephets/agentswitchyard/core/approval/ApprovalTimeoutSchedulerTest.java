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

import static org.assertj.core.api.Assertions.assertThat;

/** 超时扫描器：tick 只触发到期的记录，返回触发数量（虚拟时钟，无 sleep）。 */
@DisplayName("ApprovalTimeoutScheduler（虚拟时钟 tick）")
class ApprovalTimeoutSchedulerTest {

    private static final Instant T0 = Instant.parse("2026-10-10T08:00:00Z");

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

    @Test
    @DisplayName("到期触发、未到期不动、返回触发数")
    void tickTriggersOnlyDue() {
        MutableClock clock = new MutableClock();
        ApprovalStore store = new InMemoryApprovalStore();
        ApprovalGate gate = new ApprovalGate(store, new RoleApprovalAuthorizer(), null, clock);
        ApprovalTimeoutScheduler scheduler = new ApprovalTimeoutScheduler(gate, store, clock);

        String due =
                gate.suspend(
                        new ApprovalRequest(
                                "i1",
                                "s",
                                "risk-officer",
                                "r",
                                ApprovalTimeoutPolicy.AUTO_APPROVE,
                                Duration.ofHours(1),
                                clock.instant()));
        String notDue =
                gate.suspend(
                        new ApprovalRequest(
                                "i2",
                                "s",
                                "risk-officer",
                                "r",
                                ApprovalTimeoutPolicy.AUTO_REJECT,
                                Duration.ofHours(48),
                                clock.instant()));
        String hold =
                gate.suspend(
                        new ApprovalRequest(
                                "i3",
                                "s",
                                "risk-officer",
                                "r",
                                ApprovalTimeoutPolicy.HOLD_FOREVER,
                                Duration.ZERO,
                                clock.instant()));

        assertThat(scheduler.tick()).isZero();

        clock.advance(Duration.ofHours(2));
        assertThat(scheduler.tick()).isEqualTo(1);

        assertThat(store.find(due).orElseThrow().state()).isEqualTo(ApprovalState.TIMEOUT);
        assertThat(store.find(notDue).orElseThrow().state()).isEqualTo(ApprovalState.PENDING);
        assertThat(store.find(hold).orElseThrow().state()).isEqualTo(ApprovalState.PENDING);

        // 再 tick 一次：已决策的不会重复触发
        assertThat(scheduler.tick()).isZero();
    }
}
