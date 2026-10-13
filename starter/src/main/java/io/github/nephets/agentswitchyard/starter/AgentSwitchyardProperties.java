/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** agentswitchyard 配置（前缀 {@code agentswitchyard.*}）。 */
@ConfigurationProperties(prefix = "agentswitchyard")
public class AgentSwitchyardProperties {

    /** classpath 上的流程定义 YAML 列表（启动时注册进 FlowExecutor）。 */
    private List<String> flowDefinitions = List.of();

    /** 审批配置。 */
    private Approval approval = new Approval();

    /** 工具治理配置。 */
    private Tools tools = new Tools();

    /** 崩溃恢复配置。 */
    private Recovery recovery = new Recovery();

    public List<String> getFlowDefinitions() {
        return flowDefinitions;
    }

    public void setFlowDefinitions(List<String> flowDefinitions) {
        this.flowDefinitions = flowDefinitions == null ? List.of() : List.copyOf(flowDefinitions);
    }

    public Approval getApproval() {
        return approval;
    }

    public void setApproval(Approval approval) {
        this.approval = approval == null ? new Approval() : approval;
    }

    public Tools getTools() {
        return tools;
    }

    public void setTools(Tools tools) {
        this.tools = tools == null ? new Tools() : tools;
    }

    public Recovery getRecovery() {
        return recovery;
    }

    public void setRecovery(Recovery recovery) {
        this.recovery = recovery == null ? new Recovery() : recovery;
    }

    /** 审批：角色花名册（角色 → 可审批人集合；空 = 只校验角色一致）。 */
    public static class Approval {

        private Map<String, Set<String>> rosters = Map.of();

        /**
         * 审批待办 webhook 地址（必须是 http/https）。
         *
         * <p>null/blank → 审批通知降级为「仅日志」（默认，行为与旧版一致，不产生任何对外调用）。
         */
        private String webhookUrl;

        public Map<String, Set<String>> getRosters() {
            return rosters;
        }

        public void setRosters(Map<String, Set<String>> rosters) {
            this.rosters = rosters == null ? Map.of() : Map.copyOf(rosters);
        }

        public String getWebhookUrl() {
            return webhookUrl;
        }

        public void setWebhookUrl(String webhookUrl) {
            this.webhookUrl = webhookUrl;
        }
    }

    /** 工具治理：Agent 标识（进 SPN-001 属性）。 */
    public static class Tools {

        private String agentId = "agentswitchyard";
        private String agentVersion = "0.1.0";

        public String getAgentId() {
            return agentId;
        }

        public void setAgentId(String agentId) {
            this.agentId = agentId;
        }

        public String getAgentVersion() {
            return agentVersion;
        }

        public void setAgentVersion(String agentVersion) {
            this.agentVersion = agentVersion;
        }
    }

    /** 崩溃恢复。 */
    public static class Recovery {

        /** 启动时自动续跑 RUNNING 残留实例（kill -9 恢复的入口）。 */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** 便捷视图：rosters 的可变副本（配置绑定用）。 */
    public Map<String, Set<String>> rostersAsMap() {
        return new LinkedHashMap<>(approval.rosters);
    }
}
