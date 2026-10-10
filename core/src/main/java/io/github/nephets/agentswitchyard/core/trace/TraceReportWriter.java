/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.trace;

import io.github.nephets.agentswitchyard.core.compensation.CompensationReport;
import io.github.nephets.agentswitchyard.core.instance.StepRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 轨迹报告写出器：Markdown 报告（含每步耗时与成本 —— T02 验收标准与 ②T05 的监管导出）。 */
public final class TraceReportWriter {

    /** 创建写出器（无状态，可共享）。 */
    public TraceReportWriter() {}

    /**
     * 写 Markdown 报告。
     *
     * @param trace 执行轨迹
     * @param compensation 补偿报告（可为 null）
     * @param target 目标文件
     * @return 目标文件
     * @throws IOException 写入失败
     */
    public Path writeMarkdownReport(
            ExecutionTrace trace, CompensationReport compensation, Path target) throws IOException {
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.writeString(target, render(trace, compensation), StandardCharsets.UTF_8);
        return target;
    }

    /** 渲染 Markdown（公开供 REST 端点直接返回字符串）。 */
    public String render(ExecutionTrace trace, CompensationReport compensation) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 执行轨迹报告\n\n");
        sb.append("- 流程实例：`").append(trace.instance().flowInstanceId()).append("`\n");
        sb.append("- 流程：`")
                .append(trace.instance().flowId())
                .append("` (v")
                .append(trace.instance().flowVersion())
                .append(")\n");
        sb.append("- traceId：`").append(trace.traceId()).append("`\n");
        sb.append("- 状态：**").append(trace.instance().state()).append("**\n\n");

        sb.append("## 步骤明细\n\n");
        sb.append("| # | 步骤 | 类型 | 状态 | 耗时(ms) | 成本(CNY) | span 名 |\n");
        sb.append("|---|------|------|------|---------:|----------:|---------|\n");
        for (StepRecord step : trace.steps()) {
            sb.append("| ")
                    .append(step.seq())
                    .append(" | `")
                    .append(step.stepId())
                    .append("`")
                    .append(" | ")
                    .append(step.kind())
                    .append(" | ")
                    .append(step.state())
                    .append(" | ")
                    .append(step.durationMs())
                    .append(" | ")
                    .append(step.costCny() == null ? "0" : step.costCny().toPlainString())
                    .append(" | `")
                    .append(SwyAttributes.spanName(step.kind(), step.stepId()))
                    .append("` |\n");
        }

        sb.append("\n## 成本汇总\n\n");
        java.math.BigDecimal total =
                trace.steps().stream()
                        .map(s -> s.costCny() == null ? java.math.BigDecimal.ZERO : s.costCny())
                        .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        sb.append("- 步骤数：").append(trace.steps().size()).append("\n");
        sb.append("- 总成本：**").append(total.toPlainString()).append(" CNY**\n");
        long totalMs = trace.steps().stream().mapToLong(StepRecord::durationMs).sum();
        sb.append("- 总耗时：").append(totalMs).append(" ms\n");

        if (compensation != null && !compensation.entries().isEmpty()) {
            sb.append("\n## 补偿记录\n\n");
            sb.append("| 步骤 | 补偿动作 | 结果 | 说明 |\n");
            sb.append("|------|----------|------|------|\n");
            compensation
                    .entries()
                    .forEach(
                            e ->
                                    sb.append("| `")
                                            .append(e.stepId())
                                            .append("` | ")
                                            .append(e.actionRef())
                                            .append(" | ")
                                            .append(e.outcome().success() ? "✅ 成功" : "❌ 失败")
                                            .append(" | ")
                                            .append(
                                                    e.outcome().message() == null
                                                            ? ""
                                                            : e.outcome().message())
                                            .append(" |\n"));
        }
        return sb.toString();
    }
}
