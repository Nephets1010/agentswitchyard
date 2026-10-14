/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.starter.boot;

import io.github.nephets.agentswitchyard.starter.web.ApiExceptionAdvice;
import io.github.nephets.agentswitchyard.starter.web.ApprovalWebhookController;
import io.github.nephets.agentswitchyard.starter.web.DiagnosticsController;
import io.github.nephets.agentswitchyard.starter.web.ExecutionTraceController;
import io.github.nephets.agentswitchyard.starter.web.FlowInstanceController;
import io.github.nephets.agentswitchyard.starter.web.ReplayController;
import io.github.nephets.agentswitchyard.starter.web.ToolRegistryController;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Web 端点自动装配：把 {@code /_switchyard} 系列控制器注入任意 Servlet Web 应用。
 *
 * <p>★ 必须独立于 {@code AgentSwitchyardAutoConfiguration}：控制器只能通过自动装配注册 —— 用户应用与 starter 不同包，组件扫描扫不到
 * starter 内部（sample 集成测试曾因此 404）。 依赖的领域 bean（FlowExecutor / ApprovalGate / ToolRegistry /
 * ToolSandbox） 由 {@code AgentSwitchyardAutoConfiguration} 提供，容器按类型装配，与装配顺序无关。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
@Import({
    ApprovalWebhookController.class,
    FlowInstanceController.class,
    ExecutionTraceController.class,
    ReplayController.class,
    ToolRegistryController.class,
    DiagnosticsController.class,
    ApiExceptionAdvice.class
})
public class SwitchyardWebAutoConfiguration {}
