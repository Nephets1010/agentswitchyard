/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.starter.web;

import io.github.nephets.agentswitchyard.core.Json;
import io.github.nephets.agentswitchyard.core.executor.FlowExecutor;
import io.github.nephets.agentswitchyard.core.trace.ExecutionTrace;
import io.github.nephets.agentswitchyard.core.trace.TraceReportWriter;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 轨迹导出端点（监管 / 复盘用）：
 *
 * <ul>
 *   <li>GET /_switchyard/trace/{id}：轨迹 JSON（EVT-001 事件序列）
 *   <li>GET /_switchyard/trace/{id}/jsonl：JSONL（每步一行，喂 ③ 的 span 管线）
 *   <li>GET /_switchyard/trace/{id}/report：Markdown 报告（每步耗时与成本）
 * </ul>
 */
@RestController
@RequestMapping("/_switchyard/trace")
public class ExecutionTraceController {

    private final FlowExecutor flowExecutor;
    private final TraceReportWriter reportWriter = new TraceReportWriter();

    /** 创建控制器。 */
    public ExecutionTraceController(FlowExecutor flowExecutor) {
        this.flowExecutor = flowExecutor;
    }

    /** 轨迹 JSON。 */
    @GetMapping("/{instanceId}")
    public ResponseEntity<String> trace(@PathVariable String instanceId) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Json.write(flowExecutor.trace(instanceId).toJson()));
    }

    /** JSONL 导出（EVT-001）。 */
    @GetMapping(value = "/{instanceId}/jsonl", produces = "application/x-ndjson")
    public ResponseEntity<String> jsonl(@PathVariable String instanceId) throws Exception {
        StringBuilder sb = new StringBuilder();
        var tmp = java.nio.file.Files.createTempFile("swy-trace", ".jsonl");
        try {
            flowExecutor.trace(instanceId).writeJsonl(tmp);
            sb.append(java.nio.file.Files.readString(tmp));
        } finally {
            java.nio.file.Files.deleteIfExists(tmp);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .body(sb.toString());
    }

    /** Markdown 报告（每步耗时 + 成本，附件下载）。 */
    @GetMapping(value = "/{instanceId}/report", produces = "text/markdown")
    public ResponseEntity<String> report(@PathVariable String instanceId) {
        ExecutionTrace trace = flowExecutor.trace(instanceId);
        String markdown =
                reportWriter.render(
                        trace, flowExecutor.compensationReport(instanceId).orElse(null));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .header("Content-Disposition", "attachment; filename=trace-" + instanceId + ".md")
                .body(markdown);
    }
}
