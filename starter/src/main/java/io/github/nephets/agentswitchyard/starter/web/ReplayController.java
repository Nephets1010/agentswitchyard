/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.starter.web;

import io.github.nephets.agentswitchyard.core.Json;
import io.github.nephets.agentswitchyard.core.executor.FlowExecutor;
import io.github.nephets.agentswitchyard.core.replay.TraceDiff;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 确定性回放端点：用录制的模型响应在隔离存储里原样重跑，返回与原始执行的差异。
 *
 * <p>★ 回放不产生真实副作用（隔离 InMemoryStepStore + replay=true 属性 + 零上游模型调用）。
 */
@RestController
@RequestMapping("/_switchyard/replay")
public class ReplayController {

    private final FlowExecutor flowExecutor;

    /** 创建控制器。 */
    public ReplayController(FlowExecutor flowExecutor) {
        this.flowExecutor = flowExecutor;
    }

    /** 执行回放，返回差异报告（identical=true 表示与历史完全一致）。 */
    @PostMapping("/{instanceId}")
    public ResponseEntity<String> replay(@PathVariable String instanceId) {
        TraceDiff diff = flowExecutor.replay(instanceId);
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Json.write(diff));
    }
}
