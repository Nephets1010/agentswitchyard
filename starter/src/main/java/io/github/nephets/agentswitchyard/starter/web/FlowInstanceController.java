/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.starter.web;

import io.github.nephets.agentswitchyard.core.executor.FlowExecutor;
import io.github.nephets.agentswitchyard.core.instance.FlowInstance;
import io.github.nephets.agentswitchyard.core.instance.FlowState;
import io.github.nephets.agentswitchyard.core.instance.StepRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程实例管理端点：启动 / 查询 / 恢复。
 *
 * <p>resume 是崩溃恢复的手动入口（kill -9 后重启由自动恢复处理，也可运维显式触发）。
 */
@RestController
@RequestMapping("/_switchyard/flows")
public class FlowInstanceController {

    private static final Logger LOG = LoggerFactory.getLogger(FlowInstanceController.class);

    private final FlowExecutor flowExecutor;

    /** 创建控制器。 */
    public FlowInstanceController(FlowExecutor flowExecutor) {
        this.flowExecutor = flowExecutor;
    }

    /** 启动请求体。 */
    public record StartRequest(Map<String, Object> input, String tenantId) {}

    /** 已注册的流程。 */
    @GetMapping
    public List<Map<String, Object>> flows() {
        LOG.debug("流程列表");
        return flowExecutor.registeredFlowIds().stream()
                .map(
                        id -> {
                            Map<String, Object> view = new HashMap<>();
                            view.put("flowId", id);
                            view.put("version", flowExecutor.definition(id).version());
                            return view;
                        })
                .toList();
    }

    /** 启动流程（异步：返回实例 ID，状态经 GET 轮询）。 */
    @PostMapping("/{flowId}/start")
    public ResponseEntity<Map<String, Object>> start(
            @PathVariable String flowId, @RequestBody(required = false) StartRequest request) {
        LOG.info(
                "启动流程入口 flowId={} tenantId={}",
                flowId,
                request == null ? null : request.tenantId());
        Map<String, Object> input =
                request == null || request.input() == null ? Map.of() : request.input();
        String tenantId = request == null ? null : request.tenantId();
        String instanceId = flowExecutor.start(flowId, input, tenantId);
        Map<String, Object> body = new HashMap<>();
        body.put("flowInstanceId", instanceId);
        body.put("state", FlowState.PENDING.name());
        return ResponseEntity.accepted().body(body);
    }

    /** 查询实例 + 步骤明细。 */
    @GetMapping("/instances/{instanceId}")
    public ResponseEntity<Map<String, Object>> instance(@PathVariable String instanceId) {
        try {
            LOG.info("查询实例入口 instanceId={}", instanceId);
            FlowInstance instance = flowExecutor.instance(instanceId);
            Map<String, Object> body = new HashMap<>();
            body.put("flowInstanceId", instance.flowInstanceId());
            body.put("flowId", instance.flowId());
            body.put("flowVersion", instance.flowVersion());
            body.put("tenantId", instance.tenantId());
            body.put("traceId", instance.traceId());
            body.put("state", instance.state().name());
            body.put("startedAt", instance.startedAt().toString());
            body.put("updatedAt", instance.updatedAt().toString());
            body.put("input", instance.input());
            List<Map<String, Object>> steps = new ArrayList<>();
            for (StepRecord step : flowExecutor.steps(instanceId)) {
                Map<String, Object> stepView = new HashMap<>();
                stepView.put("stepId", step.stepId());
                stepView.put("seq", step.seq());
                stepView.put("kind", step.kind().name());
                stepView.put("state", step.state().name());
                stepView.put("idemKey", step.idemKey());
                stepView.put("attempts", step.attempts());
                stepView.put("durationMs", step.durationMs());
                stepView.put(
                        "costCny", step.costCny() == null ? "0" : step.costCny().toPlainString());
                stepView.put("outputJson", step.outputJson());
                stepView.put("errorMessage", step.errorMessage());
                steps.add(stepView);
            }
            body.put("steps", steps);
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException ex) {
            LOG.error("查询实例失败（未找到）instanceId={}", instanceId, ex);
            return ResponseEntity.notFound().build();
        }
    }

    /** 手动恢复（崩溃恢复的手动入口）。 */
    @PostMapping("/instances/{instanceId}/resume")
    public ResponseEntity<Map<String, Object>> resume(@PathVariable String instanceId) {
        LOG.info("手动恢复入口 instanceId={}", instanceId);
        flowExecutor.resume(instanceId);
        return ResponseEntity.accepted()
                .body(Map.of("resumed", true, "flowInstanceId", instanceId));
    }
}
