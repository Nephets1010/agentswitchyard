/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.trace;

import io.github.nephets.agentswitchyard.core.Json;
import io.github.nephets.agentswitchyard.core.audit.AuditEvent;
import io.github.nephets.agentswitchyard.core.definition.StepKind;
import io.github.nephets.agentswitchyard.core.instance.FlowInstance;
import io.github.nephets.agentswitchyard.core.instance.FlowState;
import io.github.nephets.agentswitchyard.core.instance.StepRecord;
import io.github.nephets.agentswitchyard.core.instance.StepState;
import io.github.nephets.agentswitchyard.core.usage.Usage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 轨迹导出：JSONL（EVT-001 契约）+ Markdown 报告（每步耗时与成本）+ SPN-001 span 属性。 */
@DisplayName("ExecutionTrace / TraceReportWriter / SwyAttributes（T04 轨迹导出）")
class TraceReportWriterTest {

    @TempDir Path tempDir;

    private static final Instant T0 = Instant.parse("2026-10-10T08:00:00Z");

    private static StepRecord step(
            String stepId, int seq, StepKind kind, long costCents, long durationMs) {
        return new StepRecord(
                "inst-1",
                stepId,
                seq,
                kind,
                stepId + "-key",
                StepState.SUCCEEDED,
                "span-" + seq,
                "{}",
                "{\"out\":\"ok\"}",
                Usage.vendorReported(100, 50),
                BigDecimal.valueOf(costCents, 2),
                1,
                T0,
                T0.plusMillis(durationMs),
                null,
                null);
    }

    private static ExecutionTrace trace() {
        FlowInstance instance =
                new FlowInstance(
                        "inst-1",
                        "credit-approve",
                        1,
                        "tenant-9",
                        "4bf92f3577b34da6a3ce929d0e0e4736",
                        FlowState.SUCCEEDED,
                        Map.of("ref", "ORD-9931"),
                        T0,
                        T0);
        return new ExecutionTrace(
                instance,
                "credit-approve",
                "1",
                List.of(
                        step("ai-screen", 0, StepKind.LLM, 3, 1200),
                        step("disburse", 1, StepKind.TOOL, 0, 45)));
    }

    @Test
    @DisplayName("★ JSONL 每行是合法的 EVT-001 事件（payloadDigest 为 sha256: 前缀）")
    void jsonlLinesAreEvt001Events() throws IOException {
        Path file = trace().writeJsonl(tempDir.resolve("trace.jsonl"));

        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(2);
        for (String line : lines) {
            AuditEvent event = Json.read(line, AuditEvent.class);
            assertThat(event.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
            assertThat(event.flowInstanceId()).isEqualTo("inst-1");
            assertThat(event.usage().totalTokens()).isEqualTo(150);
            assertThat(event.costCurrency()).isEqualTo("CNY");
            assertThat(event.payloadDigest()).startsWith("sha256:").hasSize(71);
        }
        assertThat(Json.read(lines.get(0), AuditEvent.class).eventType().name())
                .isEqualTo("MODEL_CALL");
        assertThat(Json.read(lines.get(1), AuditEvent.class).eventType().name())
                .isEqualTo("TOOL_CALL");
    }

    @Test
    @DisplayName("★ Markdown 报告含每步耗时与成本（监管导出）")
    void markdownReportContainsDurationAndCost() throws IOException {
        TraceReportWriter writer = new TraceReportWriter();
        Path file = writer.writeMarkdownReport(trace(), null, tempDir.resolve("report.md"));

        String markdown = Files.readString(file);
        assertThat(markdown).contains("执行轨迹报告");
        assertThat(markdown).contains("耗时(ms)");
        assertThat(markdown).contains("成本(CNY)");
        assertThat(markdown).contains("1200");
        assertThat(markdown).contains("0.03");
        assertThat(markdown).contains("总成本"); // 成本汇总小节
        assertThat(markdown).contains("chat ai-screen");
        assertThat(markdown).contains("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    @DisplayName(
            "★ SPN-001：span 属性携带全部 swy.flow.instance.id / swy.step.* / swy.usage / swy.cost 属性")
    void spanAttributesFollowSpn001() {
        ExecutionTrace executionTrace = trace();
        Map<String, Object> attrs = executionTrace.spanAttributes("ai-screen");

        assertThat(attrs.get(SwyAttributes.FLOW_INSTANCE_ID)).isEqualTo("inst-1");
        assertThat(attrs.get(SwyAttributes.STEP_ID)).isEqualTo("ai-screen");
        assertThat(attrs.get(SwyAttributes.STEP_SEQ)).isEqualTo(0);
        assertThat(attrs.get(SwyAttributes.STEP_KIND)).isEqualTo("LLM");
        assertThat(attrs.get(SwyAttributes.AGENT_ID)).isEqualTo("credit-approve");
        assertThat(attrs.get(SwyAttributes.AGENT_VERSION)).isEqualTo("1");
        assertThat(attrs.get(SwyAttributes.TENANT_ID)).isEqualTo("tenant-9");
        assertThat(attrs.get(SwyAttributes.USAGE_SOURCE)).isEqualTo("VENDOR_REPORTED");
        assertThat((Double) attrs.get(SwyAttributes.COST_AMOUNT)).isEqualTo(0.03d);
        assertThat(attrs.get(SwyAttributes.COST_CURRENCY)).isEqualTo("CNY");
        assertThat((String) attrs.get(SwyAttributes.PAYLOAD_DIGEST)).startsWith("sha256:");
        // 缺失维度保持缺失（禁止 "unknown" 占位）
        assertThat(attrs).doesNotContainKey(SwyAttributes.BIZ_LINE);
        assertThat(attrs).doesNotContainKey(SwyAttributes.APPROVAL_STATE);

        assertThatThrownByCheck();
    }

    private void assertThatThrownByCheck() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> trace().spanAttributes("ghost"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    @DisplayName("SPN-001 span 命名模板：{operation} {target}")
    void spanNameTemplate() {
        assertThat(SwyAttributes.spanName(StepKind.LLM, "deepseek-chat"))
                .isEqualTo("chat deepseek-chat");
        assertThat(SwyAttributes.spanName(StepKind.TOOL, "kb_lookup"))
                .isEqualTo("execute_tool kb_lookup");
        assertThat(SwyAttributes.spanName(StepKind.HUMAN, "risk_officer"))
                .isEqualTo("approval risk_officer");
        assertThat(SwyAttributes.spanName(StepKind.COMPENSATE, "cancel"))
                .isEqualTo("compensate cancel");
        assertThat(ExecutionTrace.spanNameOf(step("s", 0, StepKind.RULE, 0, 1)))
                .isEqualTo("invoke_agent s");
    }
}
