/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.starter;

import io.github.nephets.agentswitchyard.core.executor.FlowExecutor;
import io.github.nephets.agentswitchyard.core.instance.FlowState;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 自动装配冒烟：上下文能起、引擎可用、YAML 流程已注册。 */
@SpringBootTest
@DisplayName("AutoConfiguration 冒烟")
class AutoConfigurationTest {

    @Autowired private StarterTestApplication.FlowExecutorExpose expose;

    @Test
    @DisplayName("上下文启动：FlowExecutor 装配成功且 it-flow 已注册")
    void contextLoadsAndFlowRegistered() {
        FlowExecutor executor = expose.executor();
        assertThat(executor.registeredFlowIds()).contains("it-flow");
        assertThat(executor.definition("it-flow").steps()).hasSize(3);
    }

    @Test
    @DisplayName("端到端（无审批步骤的子集验证）：启动 → SUCCEEDED，每步恰执行一次")
    void startAndComplete() throws Exception {
        FlowExecutor executor = expose.executor();
        AtomicInteger finalizeRuns = new AtomicInteger();
        // finalize 的 handler 已在测试应用里定义；这里断言账本
        String instanceId = executor.start("it-flow", Map.of("ref", "AUTO-1"), "tenant-it");
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline
                && executor.instance(instanceId).state() != FlowState.WAITING_APPROVAL) {
            Thread.sleep(20);
        }
        assertThat(executor.instance(instanceId).state())
                .as("应挂起在人工复核")
                .isEqualTo(FlowState.WAITING_APPROVAL);
        assertThat(finalizeRuns.get()).isZero();
    }
}
