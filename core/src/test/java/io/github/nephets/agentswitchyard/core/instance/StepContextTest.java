/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.instance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** StepContext：handler 与可靠性层唯一交互面的契约。 */
@DisplayName("StepContext（handler 交互面）")
class StepContextTest {

    @Test
    @DisplayName("replay 属性：true / 缺省两种取值")
    void replayFlag() {
        StepContext replay =
                new StepContext(
                        "inst",
                        "flow",
                        "s1",
                        0,
                        "t",
                        "span",
                        Map.of("ref", "R"),
                        Map.of("replay", Boolean.TRUE),
                        null);
        StepContext normal =
                new StepContext("inst", "flow", "s1", 0, "t", "span", Map.of(), Map.of(), null);

        assertThat(replay.replay()).isTrue();
        assertThat(normal.replay()).isFalse();
    }

    @Test
    @DisplayName("input(key) / attribute(key)：缺键返回 null")
    void accessors() {
        StepContext context =
                new StepContext(
                        "inst",
                        "flow",
                        "s1",
                        3,
                        "t",
                        "span",
                        Map.of("ref", "R-1"),
                        Map.of("mode", "dry-run"),
                        null);

        assertThat(context.input("ref")).isEqualTo("R-1");
        assertThat(context.input("missing")).isNull();
        assertThat(context.attribute("mode")).isEqualTo("dry-run");
        assertThat(context.attribute("missing")).isNull();
        assertThat(context.flowInstanceId()).isEqualTo("inst");
        assertThat(context.stepId()).isEqualTo("s1");
        assertThat(context.seq()).isEqualTo(3);
        assertThat(context.traceId()).isEqualTo("t");
        assertThat(context.spanId()).isEqualTo("span");
    }

    @Test
    @DisplayName("null 输入归一为空 Map")
    void nullInputNormalized() {
        StepContext context =
                new StepContext("inst", "flow", "s1", 0, "t", "span", null, null, null);
        assertThat(context.input()).isEmpty();
    }

    @Test
    @DisplayName("未装配 ChatGateway 时 chat() 抛 IllegalStateException（可读错误，不 NPE）")
    void chatWithoutGatewayThrows() {
        StepContext context =
                new StepContext("inst", "flow", "s1", 0, "t", "span", Map.of(), Map.of(), null);
        assertThatThrownBy(() -> context.chat("demo-gpt", "hi"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未装配 ChatGateway");
    }
}
