/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.starter.web;

import io.github.nephets.agentswitchyard.core.Json;
import io.github.nephets.agentswitchyard.core.executor.FlowExecutor;
import io.github.nephets.agentswitchyard.core.protocol.AgentEnvelope;
import io.github.nephets.agentswitchyard.core.protocol.EnvelopeValidator;
import io.github.nephets.agentswitchyard.core.tool.ToolRegistry;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/** 诊断 + 协议校验端点：CLI doctor 与信封契约校验（首版 A2A = 单机信封 + JSON Schema 校验，P1 再上网）。 */
@RestController
@RequestMapping("/_switchyard")
public class DiagnosticsController {

    private final FlowExecutor flowExecutor;
    private final ToolRegistry toolRegistry;
    private final EnvelopeValidator envelopeValidator = new EnvelopeValidator();

    /** 创建控制器。 */
    public DiagnosticsController(FlowExecutor flowExecutor, ToolRegistry toolRegistry) {
        this.flowExecutor = flowExecutor;
        this.toolRegistry = toolRegistry;
    }

    /** doctor：引擎自检（CLI 的 doctor 子命令消费）。 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "UP");
        body.put("registeredFlows", flowExecutor.registeredFlowIds());
        body.put("registeredTools", toolRegistry.registeredNames());
        body.put("protocolVersion", AgentEnvelope.CURRENT);
        return body;
    }

    /** 信封校验（JSON Schema + 规则双校验，契约 MSG-001）。 */
    @PostMapping("/protocol/validate")
    public String validateEnvelope(@RequestBody String envelopeJson) {
        return Json.write(envelopeValidator.validateJson(envelopeJson));
    }
}
