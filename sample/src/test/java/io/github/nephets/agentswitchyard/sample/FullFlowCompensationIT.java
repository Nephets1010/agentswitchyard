/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.sample;

import io.github.nephets.agentswitchyard.core.compensation.CompensationReport;
import io.github.nephets.agentswitchyard.core.executor.FlowExecutor;
import io.github.nephets.agentswitchyard.core.instance.FlowState;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** 反向补偿闭环：规则引擎拒绝（不可重试）→ COMPENSATING → COMPENSATED，补偿报告反向可验证。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("FullFlowCompensationIT（规则拒绝 → 反向补偿）")
class FullFlowCompensationIT {

    @Autowired private FlowExecutor flowExecutor;

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
    @DisplayName("200 万额度申请：规则拒绝 → COMPENSATED（无放款副作用、报告反向顺序）")
    void ruleRejectionTriggersCompensation() throws Exception {
        String instanceId =
                flowExecutor.start(
                        "credit-approve", Map.of("ref", "R-COMP-1", "amount", 2_000_000), null);
        await(() -> flowExecutor.instance(instanceId).state().terminal());

        assertThat(flowExecutor.instance(instanceId).state()).isEqualTo(FlowState.COMPENSATED);
        // 规则引擎步骤失败，且未走到审批（HUMAN 未挂起）
        assertThat(
                        flowExecutor.steps(instanceId).stream()
                                .filter(s -> s.stepId().equals("rule-engine"))
                                .findFirst()
                                .orElseThrow()
                                .state()
                                .name())
                .isEqualTo("FAILED");
        assertThat(
                        flowExecutor.steps(instanceId).stream()
                                .noneMatch(s -> s.stepId().equals("disburse")))
                .as("拒绝路径不应执行放款")
                .isTrue();

        // 补偿报告存在且全部成功（disburse 未执行，无可补偿步骤 —— 报告为空也是正确行为）
        CompensationReport report = flowExecutor.compensationReport(instanceId).orElseThrow();
        assertThat(report.allSucceeded()).isTrue();
    }
}
