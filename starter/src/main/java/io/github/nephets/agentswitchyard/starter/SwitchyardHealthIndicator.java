/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.starter;

import io.github.nephets.agentswitchyard.core.executor.FlowExecutor;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/** 健康检查：注册的流程数 + 待审批数（运维一眼看到引擎活着、审批有没有积压）。 */
public class SwitchyardHealthIndicator implements HealthIndicator {

    private final FlowExecutor flowExecutor;
    private final io.github.nephets.agentswitchyard.core.approval.ApprovalGate approvalGate;

    /** 创建健康检查。 */
    public SwitchyardHealthIndicator(
            FlowExecutor flowExecutor,
            io.github.nephets.agentswitchyard.core.approval.ApprovalGate approvalGate) {
        this.flowExecutor = flowExecutor;
        this.approvalGate = approvalGate;
    }

    @Override
    public Health health() {
        return Health.up()
                .withDetail("registeredFlows", flowExecutor.registeredFlowIds().size())
                .withDetail("pendingApprovals", approvalGate.listPending(null).size())
                .build();
    }
}
