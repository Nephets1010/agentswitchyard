/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.trace;

import io.github.nephets.agentswitchyard.core.Json;
import io.github.nephets.agentswitchyard.core.audit.AuditEvent;
import io.github.nephets.agentswitchyard.core.audit.AuditEventType;
import io.github.nephets.agentswitchyard.core.instance.FlowInstance;
import io.github.nephets.agentswitchyard.core.instance.StepRecord;
import io.github.nephets.agentswitchyard.core.usage.Usage;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 执行轨迹：一个流程实例的完整账本视图。
 *
 * <p>首版不建控制台 UI（P1），本类负责把轨迹导出为：
 *
 * <ul>
 *   <li><b>JSONL</b>：每步一行 EVT-001 契约事件（可喂给 ③ 的 span 管线）
 *   <li><b>Markdown 报告</b>：含每步耗时与成本（监管 / 复盘直接看）
 * </ul>
 */
public final class ExecutionTrace {

    private final FlowInstance instance;
    private final String agentId;
    private final String agentVersion;
    private final List<StepRecord> steps;

    /**
     * 创建执行轨迹。
     *
     * @param instance 流程实例
     * @param agentId Agent 标识（swy.agent.id）
     * @param agentVersion Agent 版本
     * @param steps 有序步骤记录
     */
    public ExecutionTrace(
            FlowInstance instance, String agentId, String agentVersion, List<StepRecord> steps) {
        this.instance = instance;
        this.agentId = agentId;
        this.agentVersion = agentVersion;
        this.steps = steps == null ? List.of() : List.copyOf(steps);
    }

    /** 流程实例。 */
    public FlowInstance instance() {
        return instance;
    }

    /** Agent 标识。 */
    public String agentId() {
        return agentId;
    }

    /** Agent 版本。 */
    public String agentVersion() {
        return agentVersion;
    }

    /** 有序步骤记录。 */
    public List<StepRecord> steps() {
        return steps;
    }

    /** traceId（三仓同源主键）。 */
    public String traceId() {
        return instance.traceId();
    }

    /**
     * 每步的 SPN-001 span 属性（供 ③ 无损接收）。
     *
     * @param stepId 步骤 ID
     * @return 属性表；步骤不存在抛 IllegalArgumentException
     */
    public Map<String, Object> spanAttributes(String stepId) {
        StepRecord step =
                steps.stream()
                        .filter(s -> s.stepId().equals(stepId))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("轨迹中不存在步骤 " + stepId));
        return SwyAttributes.forStep(agentId, agentVersion, instance.tenantId(), step);
    }

    /**
     * 转成 EVT-001 契约事件序列（JSONL 的行内容来源）。
     *
     * @return 每步一个事件（FLOW_STEP / APPROVAL / MODEL_CALL / TOOL_CALL 按步骤类型）
     */
    public List<AuditEvent> toAuditEvents() {
        List<AuditEvent> events = new ArrayList<>();
        for (StepRecord step : steps) {
            events.add(toAuditEvent(step));
        }
        return events;
    }

    private AuditEvent toAuditEvent(StepRecord step) {
        AuditEventType type =
                switch (step.kind()) {
                    case LLM -> AuditEventType.MODEL_CALL;
                    case TOOL -> AuditEventType.TOOL_CALL;
                    case HUMAN -> AuditEventType.APPROVAL;
                    case COMPENSATE -> AuditEventType.FLOW_STEP;
                    case RULE -> AuditEventType.FLOW_STEP;
                };
        Usage usage =
                step.usage() == null
                        ? Usage.estimated(
                                io.github.nephets.agentswitchyard.core.usage.UsageSource.FALLBACK,
                                false)
                        : step.usage();
        BigDecimal cost = step.costCny() == null ? BigDecimal.ZERO : step.costCny();
        return new AuditEvent(
                type,
                instance.traceId(),
                step.spanId(),
                step.endedAt() == null ? step.startedAt() : step.endedAt(),
                instance.tenantId(),
                null,
                null,
                null,
                agentId,
                instance.flowInstanceId(),
                step.stepId(),
                step.seq(),
                usage,
                cost,
                "CNY",
                W3CTraceCodec.digest(step.outputJson()),
                null);
    }

    /**
     * 写 JSONL 文件（每行一个 EVT-001 事件）。
     *
     * @param target 目标文件
     * @return 目标文件
     * @throws IOException 写入失败
     */
    public Path writeJsonl(Path target) throws IOException {
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        StringBuilder sb = new StringBuilder();
        for (AuditEvent event : toAuditEvents()) {
            sb.append(Json.write(event)).append('\n');
        }
        Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);
        return target;
    }

    /** 序列化轨迹元数据（REST 端点返回用）。 */
    public String toJson() {
        return Json.write(
                Map.of(
                        "flowInstanceId", instance.flowInstanceId(),
                        "flowId", instance.flowId(),
                        "traceId", instance.traceId(),
                        "state", instance.state().name(),
                        "agentId", agentId == null ? "" : agentId,
                        "steps",
                                steps.stream()
                                        .map(
                                                s ->
                                                        Map.of(
                                                                "stepId", s.stepId(),
                                                                "seq", s.seq(),
                                                                "kind", s.kind().name(),
                                                                "state", s.state().name(),
                                                                "durationMs", s.durationMs(),
                                                                "costCny",
                                                                        s.costCny() == null
                                                                                ? "0"
                                                                                : s.costCny()
                                                                                        .toPlainString()))
                                        .toList()));
    }

    /** 步骤类型映射（SPN-001 span 命名用）。 */
    public static String spanNameOf(StepRecord step) {
        return SwyAttributes.spanName(step.kind(), step.stepId());
    }
}
