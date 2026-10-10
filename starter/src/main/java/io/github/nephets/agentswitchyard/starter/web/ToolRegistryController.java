/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.starter.web;

import io.github.nephets.agentswitchyard.core.Json;
import io.github.nephets.agentswitchyard.core.tool.ToolDescriptor;
import io.github.nephets.agentswitchyard.core.tool.ToolRegistry;
import io.github.nephets.agentswitchyard.core.tool.ToolSandbox;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具治理端点：注册表查询、显式注册、出域预检。
 *
 * <p>POST /tools 是第二道闸的运行时入口：高危工具不声明 requiresApproval 会在注册期被拒（HTTP 400）。
 */
@RestController
@RequestMapping("/_switchyard/tools")
public class ToolRegistryController {

    private final ToolRegistry registry;
    private final ToolSandbox sandbox;

    /** 创建控制器。 */
    public ToolRegistryController(ToolRegistry registry, ToolSandbox sandbox) {
        this.registry = registry;
        this.sandbox = sandbox;
    }

    /** 注册请求体。 */
    public record RegisterRequest(
            String toolName,
            String description,
            long timeoutMs,
            int maxRetries,
            boolean idempotent,
            boolean requiresApproval,
            Set<String> allowedDomains,
            String riskLevel) {}

    /** 全部已注册工具。 */
    @GetMapping
    public List<ToolDescriptor> all() {
        return registry.all();
    }

    /** 显式注册（未注册的工具调用被 sandbox 拒绝 —— 三道闸之一）。 */
    @PostMapping
    public ResponseEntity<String> register(@RequestBody RegisterRequest request) {
        ToolDescriptor descriptor =
                new ToolDescriptor(
                        request.toolName(),
                        request.description(),
                        Duration.ofMillis(request.timeoutMs() <= 0 ? 10_000 : request.timeoutMs()),
                        Math.max(0, request.maxRetries()),
                        request.idempotent(),
                        request.requiresApproval(),
                        request.allowedDomains() == null ? Set.of() : request.allowedDomains(),
                        request.riskLevel() == null
                                ? io.github.nephets.agentswitchyard.core.tool.RiskLevel.LOW
                                : io.github.nephets.agentswitchyard.core.tool.RiskLevel.valueOf(
                                        request.riskLevel()));
        registry.register(descriptor);
        return ResponseEntity.ok(
                Json.write(Map.of("registered", true, "toolName", descriptor.toolName())));
    }

    /** 出域预检（checkDomain：空 allowedDomains = 禁止全部外呼）。 */
    @PostMapping("/{toolName}/check")
    public ResponseEntity<String> check(@PathVariable String toolName, @RequestParam String url) {
        ToolDescriptor descriptor = sandbox.guard(toolName, url);
        return ResponseEntity.ok(
                Json.write(Map.of("allowed", true, "toolName", descriptor.toolName(), "url", url)));
    }
}
