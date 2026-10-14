/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.starter.web;

import io.github.nephets.agentswitchyard.core.Json;
import io.github.nephets.agentswitchyard.core.approval.ApprovalGate;
import io.github.nephets.agentswitchyard.core.approval.InMemoryApprovalStore;
import io.github.nephets.agentswitchyard.core.approval.RoleApprovalAuthorizer;
import io.github.nephets.agentswitchyard.core.compensation.CompensationChain;
import io.github.nephets.agentswitchyard.core.definition.FlowDefinition;
import io.github.nephets.agentswitchyard.core.executor.FlowExecutor;
import io.github.nephets.agentswitchyard.core.retry.DefaultErrorClassifier;
import io.github.nephets.agentswitchyard.core.store.InMemoryStepStore;
import io.github.nephets.agentswitchyard.core.tool.ToolRegistry;
import io.github.nephets.agentswitchyard.core.tool.ToolSandbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 控制器层单测（standalone，不启容器）：诊断 + 工具治理 + 轨迹导出 + 协议校验。 */
@DisplayName("web 控制器（standalone）")
class WebControllersTest {

    private FlowExecutor executor;
    private ToolRegistry registry;
    private ToolSandbox sandbox;
    private ToolRegistryController toolController;
    private DiagnosticsController diagnosticsController;
    private ExecutionTraceController traceController;
    private FlowInstanceController flowController;

    @BeforeEach
    void setUp() {
        ApprovalGate gate =
                new ApprovalGate(
                        new InMemoryApprovalStore(),
                        new RoleApprovalAuthorizer(),
                        null,
                        Clock.systemUTC());
        executor =
                new FlowExecutor(
                        new InMemoryStepStore(),
                        Map.of(
                                "only",
                                ctx ->
                                        io.github.nephets.agentswitchyard.core.instance.StepResult
                                                .ofRule(Map.of())),
                        gate,
                        new CompensationChain(Map.of(), Clock.systemUTC()),
                        null,
                        new DefaultErrorClassifier(),
                        Clock.systemUTC(),
                        null);
        executor.register(FlowDefinition.builder("demo-flow").ruleStep("only").done().build());
        registry = new ToolRegistry();
        sandbox = new ToolSandbox(registry);
        toolController = new ToolRegistryController(registry, sandbox);
        diagnosticsController = new DiagnosticsController(executor, registry);
        traceController = new ExecutionTraceController(executor);
        flowController = new FlowInstanceController(executor);
    }

    @Test
    @DisplayName("DiagnosticsController：health 与信封校验（合法 / 缺字段）")
    void diagnostics() {
        assertThat(diagnosticsController.health())
                .containsEntry("status", "UP")
                .containsEntry("protocolVersion", "1.0");

        String valid =
                Json.write(
                        new io.github.nephets.agentswitchyard.core.protocol.AgentEnvelope(
                                "1.0",
                                null,
                                "agent-a",
                                "agent-b",
                                "review.request",
                                "a".repeat(32),
                                "idem-1",
                                java.time.Instant.now(),
                                Map.of("ref", "R-1")));
        assertThat(diagnosticsController.validateEnvelope(valid)).contains("\"valid\":true");

        String broken = "{\"protocolVersion\":\"9.9\",\"messageId\":\"x\"}";
        assertThat(diagnosticsController.validateEnvelope(broken)).contains("\"valid\":false");
    }

    @Test
    @DisplayName("ToolRegistryController：all / register / check（空域白名单 = 禁止全部外呼）")
    void tools() {
        assertThat(toolController.all()).isEmpty();

        var registerBody =
                new ToolRegistryController.RegisterRequest(
                        "kb_lookup", "检索", 1000L, 0, true, false, Set.of(), "LOW");
        assertThat(toolController.register(registerBody).getBody()).contains("\"registered\":true");
        assertThat(toolController.all()).hasSize(1);

        // 空 allowedDomains：任何 URL 都拒绝（第二道闸）
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> toolController.check("kb_lookup", "https://example.com/api"))
                .isInstanceOf(RuntimeException.class);

        // 未注册工具：拒绝（第一道闸）
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> toolController.check("ghost", "https://example.com"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("ToolRegistryController.register：高危无审批声明 → 注册期拒绝（异常上抛）")
    void highRiskRegistrationRejected() {
        var body =
                new ToolRegistryController.RegisterRequest(
                        "boom", "", 0L, 0, true, false, Set.of(), "HIGH");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> toolController.register(body))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ExecutionTraceController：trace / jsonl / report 三种导出")
    void traceExports() throws Exception {
        String instanceId = executor.start("demo-flow", Map.of("ref", "R-1"), null);
        long deadline = System.currentTimeMillis() + 10_000;
        while (executor.instance(instanceId).state()
                != io.github.nephets.agentswitchyard.core.instance.FlowState.SUCCEEDED) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("10s 内未完成");
            }
            Thread.sleep(10);
        }

        assertThat(traceController.trace(instanceId).getBody()).contains("demo-flow");
        assertThat(traceController.jsonl(instanceId).getBody()).hasLineCount(1);
        assertThat(traceController.report(instanceId).getBody()).contains("执行轨迹报告");
    }

    @Test
    @DisplayName("FlowInstanceController：flows 列表 / start / instance / 404")
    void flows() throws Exception {
        assertThat(flowController.flows()).hasSize(1);

        var started =
                flowController.start(
                        "demo-flow",
                        new FlowInstanceController.StartRequest(Map.of("ref", "R-9"), "t-1"));
        String instanceId = started.getBody().get("flowInstanceId").toString();

        long deadline = System.currentTimeMillis() + 10_000;
        while (executor.instance(instanceId).state()
                != io.github.nephets.agentswitchyard.core.instance.FlowState.SUCCEEDED) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("10s 内未完成");
            }
            Thread.sleep(10);
        }
        assertThat(flowController.instance(instanceId).getBody())
                .containsEntry("state", "SUCCEEDED");
        assertThat(flowController.instance("no-such").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("ApiExceptionAdvice：错误码 → HTTP 状态映射")
    void advice() {
        ApiExceptionAdvice advice = new ApiExceptionAdvice();
        assertThat(
                        ApiExceptionAdvice.statusOf(
                                io.github.nephets.agentswitchyard.core.ErrorCode.SW_BAD_REQUEST))
                .isEqualTo(400);
        assertThat(
                        ApiExceptionAdvice.statusOf(
                                io.github.nephets.agentswitchyard.core.ErrorCode
                                        .SW_APPROVAL_DENIED))
                .isEqualTo(403);
        assertThat(
                        ApiExceptionAdvice.statusOf(
                                io.github.nephets.agentswitchyard.core.ErrorCode
                                        .SW_MODEL_UNAVAILABLE))
                .isEqualTo(503);
        assertThat(
                        ApiExceptionAdvice.statusOf(
                                io.github.nephets.agentswitchyard.core.ErrorCode.SW_INTERNAL))
                .isEqualTo(500);

        var response =
                advice.handleSwitchyard(
                        new io.github.nephets.agentswitchyard.core.SwitchyardException(
                                io.github.nephets.agentswitchyard.core.ErrorCode.SW_BAD_REQUEST,
                                "bad",
                                null));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("errorCode", "SW_BAD_REQUEST");
    }
}
