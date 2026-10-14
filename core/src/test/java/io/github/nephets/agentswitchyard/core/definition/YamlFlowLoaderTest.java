/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.definition;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** YAML 流程定义加载（让非 Java 团队也能声明流程）。 */
@DisplayName("YamlFlowLoader（T02 验收标准 6）")
class YamlFlowLoaderTest {

    @Test
    @DisplayName("加载 credit-approve.yaml 得到 5 步流程，第 4 步 requiresApproval=true")
    void loadsCreditApproveSample() {
        FlowDefinition flow = YamlFlowLoader.loadFromClasspath("samples/credit-approve.yaml");

        assertThat(flow.flowId()).isEqualTo("credit-approve");
        assertThat(flow.steps()).hasSize(5);
        assertThat(flow.steps())
                .extracting(StepDefinition::stepId)
                .containsExactly(
                        "ai-screen", "risk-score", "rule-engine", "manual-review", "disburse");
        assertThat(flow.steps())
                .extracting(StepDefinition::kind)
                .containsExactly(
                        StepKind.LLM, StepKind.LLM, StepKind.RULE, StepKind.HUMAN, StepKind.TOOL);

        StepDefinition review = flow.step("manual-review");
        assertThat(review.requiresApproval()).isTrue();
        assertThat(review.approvalRole()).isEqualTo("risk-officer");

        StepDefinition disburse = flow.step("disburse");
        assertThat(disburse.compensation().actionRef()).isEqualTo("cancelReserve");
        assertThat(disburse.resolveIdempotencyKey(java.util.Map.of("ref", "ORD-9931")))
                .isEqualTo("ORD-9931:disburse");
        assertThat(flow.step("ai-screen").retry().maxAttempts()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("缺 flowId / steps 缺失 / kind 非法 → 加载期即抛可读错误")
    void rejectsBrokenDefinitions() {
        assertThatThrownBy(() -> YamlFlowLoader.load("version: 1\nsteps: []"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flowId");

        assertThatThrownBy(() -> YamlFlowLoader.load("flowId: f\nversion: 1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("steps");

        assertThatThrownBy(
                        () ->
                                YamlFlowLoader.load(
                                        """
                                        flowId: f
                                        steps:
                                          - stepId: s1
                                            kind: BRANCH
                                        """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kind");

        assertThatThrownBy(() -> YamlFlowLoader.loadFromClasspath("no/such/file.yaml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("classpath");
    }
}
