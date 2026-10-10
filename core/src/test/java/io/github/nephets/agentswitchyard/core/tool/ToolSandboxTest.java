/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.tool;

import io.github.nephets.agentswitchyard.core.ErrorCode;
import io.github.nephets.agentswitchyard.core.SwitchyardException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 工具治理三道闸（T04 验收标准 3 的三条硬断言）： 未注册不可调 / 超白名单被拦 / 高危无审批声明注册时即拒。 */
@DisplayName("ToolSandbox 三道闸（对抗 Prompt 注入的第一道防线）")
class ToolSandboxTest {

    private final ToolRegistry registry = new ToolRegistry();
    private final ToolSandbox sandbox = new ToolSandbox(registry);

    private void registerLowRisk() {
        registry.register(
                new ToolDescriptor(
                        "kb_lookup",
                        "知识库检索",
                        Duration.ofSeconds(5),
                        0,
                        true,
                        false,
                        Set.of("kb.internal.example.com"),
                        RiskLevel.LOW));
    }

    @Test
    @DisplayName("★ 第一道闸：未注册工具不可被调用（SW_TOOL_DENIED）")
    void unregisteredToolDenied() {
        assertThatThrownBy(() -> sandbox.checkRegistered("evil_data_wipe"))
                .isInstanceOf(SwitchyardException.class)
                .satisfies(
                        ex ->
                                assertThat(((SwitchyardException) ex).errorCode())
                                        .isEqualTo(ErrorCode.SW_TOOL_DENIED));
    }

    @Test
    @DisplayName("★ 第二道闸：超出 allowedDomains 的请求被拦截")
    void domainOutsideAllowlistBlocked() {
        registerLowRisk();
        ToolDescriptor descriptor = sandbox.checkRegistered("kb_lookup");

        // 白名单内（精确）
        sandbox.checkDomain(descriptor, "https://kb.internal.example.com/api?q=x");
        // 白名单内（子域）
        sandbox.checkDomain(descriptor, "https://a.kb.internal.example.com/api");
        // 本地调用（无目标）放行
        sandbox.checkDomain(descriptor, null);
        // 超出白名单
        assertThatThrownBy(() -> sandbox.checkDomain(descriptor, "https://evil.example.com/steal"))
                .isInstanceOf(SwitchyardException.class)
                .hasMessageContaining("白名单");
        // 相似域名（非子域）不算命中
        assertThatThrownBy(
                        () ->
                                sandbox.checkDomain(
                                        descriptor, "https://kb.internal.example.com.evil.io/x"))
                .isInstanceOf(SwitchyardException.class);
    }

    @Test
    @DisplayName("★ 默认拒绝：allowedDomains 为空集合 = 禁止全部外呼")
    void emptyAllowlistDeniesAllOutbound() {
        registry.register(
                new ToolDescriptor(
                        "local_calc",
                        "本地计算",
                        Duration.ofSeconds(5),
                        0,
                        true,
                        false,
                        Set.of(),
                        RiskLevel.LOW));
        ToolDescriptor descriptor = sandbox.checkRegistered("local_calc");

        sandbox.checkDomain(descriptor, null); // 本地调用可以
        assertThatThrownBy(() -> sandbox.checkDomain(descriptor, "https://any.example.com"))
                .isInstanceOf(SwitchyardException.class)
                .hasMessageContaining("默认禁止全部外呼");
    }

    @Test
    @DisplayName("★ 第三道闸：HIGH/CRITICAL 且未声明 requiresApproval 的工具注册时即拒绝")
    void highRiskWithoutApprovalDeclarationRejectedAtRegistration() {
        assertThatThrownBy(
                        () ->
                                registry.register(
                                        new ToolDescriptor(
                                                "disburse_loan",
                                                "放款",
                                                Duration.ofSeconds(10),
                                                0,
                                                false,
                                                false,
                                                Set.of(),
                                                RiskLevel.CRITICAL)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requiresApproval=true");
        // 未注册成功
        assertThat(registry.registeredNames()).doesNotContain("disburse_loan");

        // 声明了审批的高危工具可以注册
        registry.register(
                new ToolDescriptor(
                        "disburse_loan",
                        "放款",
                        Duration.ofSeconds(10),
                        0,
                        false,
                        true,
                        Set.of("bank.internal"),
                        RiskLevel.HIGH));
        assertThat(registry.registeredNames()).contains("disburse_loan");
        // 第三道闸复核兜底（不抛异常即通过）
        sandbox.checkApprovalDeclared(registry.find("disburse_loan").orElseThrow());
    }

    @Test
    @DisplayName("guard() 三闸一次过；重复注册被拒绝")
    void guardAndDuplicateRegistration() {
        registerLowRisk();
        assertThat(sandbox.guard("kb_lookup", "https://kb.internal.example.com").toolName())
                .isEqualTo("kb_lookup");

        assertThatThrownBy(
                        () ->
                                registry.register(
                                        new ToolDescriptor(
                                                "kb_lookup",
                                                "dup",
                                                Duration.ofSeconds(1),
                                                0,
                                                true,
                                                false,
                                                Set.of(),
                                                RiskLevel.LOW)))
                .isInstanceOf(SwitchyardException.class)
                .hasMessageContaining("已注册");

        assertThat(registry.all()).hasSize(1);
        assertThat(ToolDescriptor.readOnly("r", "只读").riskLevel()).isEqualTo(RiskLevel.LOW);
    }
}
