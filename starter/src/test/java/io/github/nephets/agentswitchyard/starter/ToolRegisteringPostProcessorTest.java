/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.starter;

import io.github.nephets.agentswitchyard.core.tool.RiskLevel;
import io.github.nephets.agentswitchyard.core.tool.SwitchyardTool;
import io.github.nephets.agentswitchyard.core.tool.ToolRegistry;
import io.github.nephets.agentswitchyard.core.tool.ToolSandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 工具注册处理器：三道闸中的第一道（显式注册 + 注册期 fail-fast）。 */
@DisplayName("ToolRegisteringPostProcessor（注册期治理）")
class ToolRegisteringPostProcessorTest {

    static class ValidBean {

        @SwitchyardTool(toolName = "kb_lookup", description = "知识库检索", riskLevel = RiskLevel.LOW)
        public String lookup(String keyword) {
            return keyword;
        }
    }

    static class HighRiskWithoutApprovalBean {

        @SwitchyardTool(
                toolName = "disaster",
                riskLevel = RiskLevel.CRITICAL,
                requiresApproval = false)
        public void doomed() {
            // 高危工具没声明审批 —— 注册期必须拒绝
        }
    }

    @Test
    @DisplayName("扫描注解方法并注册；注册后 sandbox 可见")
    void registersAnnotatedMethods() {
        ToolRegistry registry = new ToolRegistry();
        new ToolRegisteringPostProcessor(registry)
                .postProcessAfterInitialization(new ValidBean(), "validBean");

        assertThat(registry.registeredNames()).containsExactly("kb_lookup");
        assertThat(registry.find("kb_lookup").orElseThrow().riskLevel()).isEqualTo(RiskLevel.LOW);

        // 未注册工具调用被 sandbox 拒绝（第二道闸协同验证）
        ToolSandbox sandbox = new ToolSandbox(registry);
        assertThatThrownBy(() -> sandbox.guard("not_registered", "https://example.com"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("无注解的 bean：注册表不受影响")
    void ignoresPlainBeans() {
        ToolRegistry registry = new ToolRegistry();
        new ToolRegisteringPostProcessor(registry)
                .postProcessAfterInitialization(new Object(), "plain");
        assertThat(registry.registeredNames()).isEmpty();
    }

    @Test
    @DisplayName("★ 高危工具未声明 requiresApproval：注册期抛 IllegalArgumentException（fail-fast）")
    void highRiskWithoutApprovalRejectedAtRegistration() {
        ToolRegistry registry = new ToolRegistry();
        ToolRegisteringPostProcessor processor = new ToolRegisteringPostProcessor(registry);
        assertThatThrownBy(
                        () ->
                                processor.postProcessAfterInitialization(
                                        new HighRiskWithoutApprovalBean(), "badBean"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requiresApproval=true");
        assertThat(registry.registeredNames()).as("注册失败的工具不应进入注册表").doesNotContain("disaster");
    }
}
