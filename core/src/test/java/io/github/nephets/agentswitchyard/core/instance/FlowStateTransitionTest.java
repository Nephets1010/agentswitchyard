/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.instance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 流程状态机：非法转移在写入前就抛异常。 */
@DisplayName("FlowState 状态机（T02 验收标准 7）")
class FlowStateTransitionTest {

    @Test
    @DisplayName("合法主干：PENDING→RUNNING→WAITING_APPROVAL→RUNNING→SUCCEEDED")
    void happyPathTransitions() {
        assertThat(FlowState.PENDING.transitionTo(FlowState.RUNNING)).isEqualTo(FlowState.RUNNING);
        assertThat(FlowState.RUNNING.transitionTo(FlowState.WAITING_APPROVAL))
                .isEqualTo(FlowState.WAITING_APPROVAL);
        assertThat(FlowState.WAITING_APPROVAL.transitionTo(FlowState.RUNNING))
                .isEqualTo(FlowState.RUNNING);
        assertThat(FlowState.RUNNING.transitionTo(FlowState.SUCCEEDED))
                .isEqualTo(FlowState.SUCCEEDED);
    }

    @Test
    @DisplayName("★ 非法转移（如 PENDING→COMPENSATING）抛 IllegalStateException")
    void illegalTransitionsThrow() {
        assertThatThrownBy(() -> FlowState.PENDING.transitionTo(FlowState.COMPENSATING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING");
        assertThatThrownBy(() -> FlowState.SUCCEEDED.transitionTo(FlowState.RUNNING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUCCEEDED");
        assertThatThrownBy(() -> FlowState.COMPENSATED.transitionTo(FlowState.COMPENSATING))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> FlowState.RUNNING.transitionTo(null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("失败→补偿→终态链路合法；补偿失败可达 COMPENSATE_FAILED")
    void compensationPath() {
        FlowState.RUNNING.transitionTo(FlowState.FAILED);
        FlowState.FAILED.transitionTo(FlowState.COMPENSATING);
        FlowState.COMPENSATING.transitionTo(FlowState.COMPENSATED);
        FlowState.COMPENSATING.transitionTo(FlowState.COMPENSATE_FAILED);

        assertThat(FlowState.SUCCEEDED.terminal()).isTrue();
        assertThat(FlowState.COMPENSATED.terminal()).isTrue();
        assertThat(FlowState.COMPENSATE_FAILED.terminal()).isTrue();
        assertThat(FlowState.RUNNING.terminal()).isFalse();
        assertThat(FlowState.REJECTED.transitionTo(FlowState.COMPENSATING))
                .isEqualTo(FlowState.COMPENSATING);
    }

    @Test
    @DisplayName("StepState 的成功 / 补偿判定")
    void stepStatePredicates() {
        assertThat(StepState.SUCCEEDED.succeeded()).isTrue();
        assertThat(StepState.RUNNING.succeeded()).isFalse();
        assertThat(StepState.COMPENSATED.compensated()).isTrue();
        assertThat(StepState.COMPENSATE_FAILED.compensated()).isFalse();
    }
}
