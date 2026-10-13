/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.starter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 配置属性：默认值 + 绑定归一（null 输入不允许炸掉启动）。 */
@DisplayName("AgentSwitchyardProperties")
class AgentSwitchyardPropertiesTest {

    @Test
    @DisplayName("默认值：recovery 开启、无流程定义、空花名册、agent 标识齐备")
    void defaults() {
        AgentSwitchyardProperties properties = new AgentSwitchyardProperties();
        assertThat(properties.getFlowDefinitions()).isEmpty();
        assertThat(properties.getApproval().getRosters()).isEmpty();
        assertThat(properties.getRecovery().isEnabled()).isTrue();
        assertThat(properties.getTools().getAgentId()).isEqualTo("agentswitchyard");
        assertThat(properties.getTools().getAgentVersion()).isNotBlank();
    }

    @Test
    @DisplayName("setter 归一：null 输入归一为空集合")
    void nullNormalization() {
        AgentSwitchyardProperties properties = new AgentSwitchyardProperties();
        properties.setFlowDefinitions(null);
        properties.setApproval(null);
        properties.setTools(null);
        properties.setRecovery(null);

        assertThat(properties.getFlowDefinitions()).isEmpty();
        assertThat(properties.getApproval()).isNotNull();
        assertThat(properties.getTools()).isNotNull();
        assertThat(properties.getRecovery()).isNotNull();
    }

    @Test
    @DisplayName("审批 webhook：默认未配置（仅日志），可显式绑定 http/https 地址")
    void approvalWebhookUrl() {
        AgentSwitchyardProperties properties = new AgentSwitchyardProperties();
        assertThat(properties.getApproval().getWebhookUrl())
                .as("默认 null → 与旧版一致，审批通知不产生任何对外调用")
                .isNull();

        properties.getApproval().setWebhookUrl("https://hooks.example.test/approval");
        assertThat(properties.getApproval().getWebhookUrl())
                .isEqualTo("https://hooks.example.test/approval");
    }

    @Test
    @DisplayName("花名册绑定 + rostersAsMap 便捷视图")
    void rosters() {
        AgentSwitchyardProperties properties = new AgentSwitchyardProperties();
        properties.getApproval().setRosters(Map.of("risk-officer", Set.of("zhang-san")));
        properties.setFlowDefinitions(List.of("classpath:flows/a.yaml"));

        assertThat(properties.rostersAsMap()).containsKey("risk-officer");
        assertThat(properties.getFlowDefinitions()).containsExactly("classpath:flows/a.yaml");
    }
}
