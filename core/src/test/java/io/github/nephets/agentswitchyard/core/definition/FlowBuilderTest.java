/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.definition;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 流程定义 Builder 与 fail-fast 校验。 */
@DisplayName("FlowDefinition / FlowBuilder（定义期错误绝不允许拖到执行期）")
class FlowBuilderTest {

    @Test
    @DisplayName("20 行 DSL 定义一个 5 步流程（README 30 秒上手的承诺）")
    void builderBuildsOrderedSteps() {
        FlowDefinition flow =
                FlowDefinition.builder("credit-approve")
                        .ruleStep("rule-engine")
                        .description("规则引擎")
                        .done()
                        .humanStep("manual-review")
                        .approvalRequired("risk-officer")
                        .description("人工复核")
                        .done()
                        .toolStep("disburse")
                        .idempotencyKey("{ref}:disburse")
                        .compensateWith("cancelReserve")
                        .timeout(Duration.ofSeconds(30))
                        .retry(RetrySpec.DEFAULT)
                        .done()
                        .build();

        assertThat(flow.flowId()).isEqualTo("credit-approve");
        assertThat(flow.version()).isEqualTo(1);
        assertThat(flow.steps()).hasSize(3);
        assertThat(flow.steps()).extracting(StepDefinition::order).containsExactly(0, 1, 2);
        assertThat(flow.step("manual-review").requiresApproval()).isTrue();
        assertThat(flow.step("manual-review").approvalRole()).isEqualTo("risk-officer");
        assertThat(flow.step("disburse").compensation().actionRef()).isEqualTo("cancelReserve");
    }

    @Test
    @DisplayName("stepId 重复即拒绝")
    void duplicateStepIdRejected() {
        assertThatThrownBy(
                        () ->
                                FlowDefinition.builder("f")
                                        .ruleStep("s1")
                                        .done()
                                        .ruleStep("s1")
                                        .done()
                                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");
    }

    @Test
    @DisplayName("步骤序号必须连续（YAML 删一行漏一个号会被立即发现）")
    void nonContinuousOrderRejected() {
        StepDefinition broken =
                new StepDefinition(
                        "s1", StepKind.RULE, 5, null, false, null, null, null, null, null);
        assertThatThrownBy(
                        () ->
                                new FlowDefinition(
                                        "f",
                                        1,
                                        java.util.List.of(
                                                broken,
                                                new StepDefinition(
                                                        "s2",
                                                        StepKind.RULE,
                                                        6,
                                                        null,
                                                        false,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("order");
    }

    @Test
    @DisplayName("HUMAN 步骤声明审批但缺角色 → 定义期拒绝")
    void approvalWithoutRoleRejected() {
        assertThatThrownBy(
                        () ->
                                new StepDefinition(
                                        "s1",
                                        StepKind.HUMAN,
                                        0,
                                        null,
                                        true,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approvalRole");
    }

    @Test
    @DisplayName("TOOL 步骤（有外部副作用）不声明补偿 → 定义期拒绝")
    void toolWithoutCompensationRejected() {
        assertThatThrownBy(
                        () ->
                                new StepDefinition(
                                        "s1",
                                        StepKind.TOOL,
                                        0,
                                        null,
                                        false,
                                        null,
                                        null,
                                        null,
                                        CompensationSpec.NONE,
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("补偿");
    }

    @Test
    @DisplayName("幂等键模板：{ref} 占位符按流程输入解析")
    void idempotencyKeyTemplate() {
        StepDefinition step =
                FlowDefinition.builder("f")
                        .toolStep("disburse")
                        .idempotencyKey("{ref}:disburse")
                        .compensateWith("cancel")
                        .done()
                        .build()
                        .step("disburse");
        assertThat(step.resolveIdempotencyKey(Map.of("ref", "ORD-9931")))
                .isEqualTo("ORD-9931:disburse");
        assertThat(step.resolveIdempotencyKey(Map.of())).isEqualTo("null:disburse");
        // 未声明幂等键时默认 stepId
        StepDefinition noKey = FlowDefinition.builder("f").ruleStep("s1").done().build().step("s1");
        assertThat(noKey.resolveIdempotencyKey(Map.of("ref", "X"))).isEqualTo("s1");
    }

    @Test
    @DisplayName("空流程拒绝；RetrySpec 退避按指数递增且封顶")
    void miscValidation() {
        assertThatThrownBy(() -> new FlowDefinition("f", 1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少");
        RetrySpec spec = new RetrySpec(5, 100, 300, 2.0);
        assertThat(spec.backoffMs(1)).isEqualTo(100);
        assertThat(spec.backoffMs(2)).isEqualTo(200);
        assertThat(spec.backoffMs(3)).isEqualTo(300);
        assertThat(spec.backoffMs(4)).isEqualTo(300); // 封顶
        assertThatThrownBy(
                        () ->
                                new FlowDefinition(
                                        "f",
                                        0,
                                        java.util.List.of(
                                                new StepDefinition(
                                                        "s1",
                                                        StepKind.RULE,
                                                        0,
                                                        null,
                                                        false,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }
}
