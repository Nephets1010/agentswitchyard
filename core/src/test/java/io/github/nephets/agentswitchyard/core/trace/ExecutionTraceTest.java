/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.trace;

import io.github.nephets.agentswitchyard.core.definition.StepKind;
import io.github.nephets.agentswitchyard.core.instance.FlowInstance;
import io.github.nephets.agentswitchyard.core.instance.FlowState;
import io.github.nephets.agentswitchyard.core.instance.StepRecord;
import io.github.nephets.agentswitchyard.core.instance.StepState;
import io.github.nephets.agentswitchyard.core.usage.Usage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ExecutionTrace：EVT-001 JSONL 导出、SPN-001 span 属性、JSON 视图。 */
@DisplayName("ExecutionTrace（轨迹导出）")
class ExecutionTraceTest {

    private static StepRecord step(String stepId, int seq) {
        return new StepRecord(
                "inst-1",
                stepId,
                seq,
                StepKind.LLM,
                "key-" + stepId,
                StepState.SUCCEEDED,
                "span-" + seq,
                "{}",
                "{\"out\":1}",
                Usage.of(10, 20),
                new BigDecimal("0.00010000"),
                1,
                Instant.parse("2026-10-10T08:00:00Z"),
                Instant.parse("2026-10-10T08:00:01Z"),
                null,
                null);
    }

    private static ExecutionTrace trace() {
        FlowInstance instance =
                new FlowInstance(
                        "inst-1",
                        "demo-flow",
                        2,
                        "tenant-1",
                        "a".repeat(32),
                        FlowState.SUCCEEDED,
                        Map.of("ref", "R-1"),
                        Instant.parse("2026-10-10T08:00:00Z"),
                        Instant.parse("2026-10-10T08:00:03Z"));
        return new ExecutionTrace(
                instance, "demo-flow", "2", List.of(step("s1", 0), step("s2", 1)));
    }

    @Test
    @DisplayName("toAuditEvents：每步一条 EVT-001 事件，usage 不可为 null")
    void toAuditEvents() {
        var events = trace().toAuditEvents();
        assertThat(events).hasSize(2);
        assertThat(events.get(0).eventType()).isNotNull();
    }

    @Test
    @DisplayName("writeJsonl：每行一个事件（JSONL），可再次解析")
    void writeJsonl() throws Exception {
        java.nio.file.Path target = Files.createTempFile("swy-trace", ".jsonl");
        try {
            trace().writeJsonl(target);
            List<String> lines = Files.readAllLines(target);
            assertThat(lines).hasSize(2);
            // 每行都是合法 JSON
            io.github.nephets.agentswitchyard.core.Json.read(lines.get(0), Map.class);
        } finally {
            Files.deleteIfExists(target);
        }
    }

    @Test
    @DisplayName("spanAttributes：包含本仓独有 swy.flow.instance.id / swy.step.*")
    void spanAttributes() {
        Map<String, Object> attrs = trace().spanAttributes("s1");
        assertThat(attrs).containsEntry(SwyAttributes.FLOW_INSTANCE_ID, "inst-1");
        assertThat(attrs).containsEntry(SwyAttributes.STEP_ID, "s1");
        assertThat(attrs).containsEntry(SwyAttributes.AGENT_ID, "demo-flow");
    }

    @Test
    @DisplayName("spanAttributes：步骤不存在抛 IllegalArgumentException")
    void spanAttributesUnknownStep() {
        assertThatThrownBy(() -> trace().spanAttributes("no-such"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("轨迹中不存在步骤");
    }

    @Test
    @DisplayName("toJson：合法 JSON，含 traceId")
    void toJson() {
        String json = trace().toJson();
        assertThat(json).contains("a".repeat(8));
        io.github.nephets.agentswitchyard.core.Json.read(json, Map.class);
    }
}
