/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.starter;

import io.github.nephets.agentswitchyard.core.approval.ApprovalGate;
import io.github.nephets.agentswitchyard.core.approval.InMemoryApprovalStore;
import io.github.nephets.agentswitchyard.core.approval.RoleApprovalAuthorizer;
import io.github.nephets.agentswitchyard.core.executor.FlowExecutor;
import io.github.nephets.agentswitchyard.core.store.InMemoryStepStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 健康检查：UP + 注册流程数 + 待审批数。 */
@DisplayName("SwitchyardHealthIndicator")
class SwitchyardHealthIndicatorTest {

    @Test
    @DisplayName("UP 且带 registeredFlows / pendingApprovals 明细")
    void upWithDetails() {
        FlowExecutor executor =
                new FlowExecutor(
                        new InMemoryStepStore(),
                        Map.of(),
                        new ApprovalGate(
                                new InMemoryApprovalStore(),
                                new RoleApprovalAuthorizer(),
                                null,
                                Clock.systemUTC()),
                        new io.github.nephets.agentswitchyard.core.compensation.CompensationChain(
                                Map.of(), Clock.systemUTC()),
                        null,
                        new io.github.nephets.agentswitchyard.core.retry.DefaultErrorClassifier(),
                        Clock.systemUTC(),
                        null);

        SwitchyardHealthIndicator indicator =
                new SwitchyardHealthIndicator(
                        executor,
                        new ApprovalGate(
                                new InMemoryApprovalStore(),
                                new RoleApprovalAuthorizer(),
                                null,
                                Clock.systemUTC()));
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("registeredFlows", 0);
        assertThat(health.getDetails()).containsKey("pendingApprovals");
    }
}
