/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.sample;

import io.github.nephets.agentswitchyard.core.approval.ApprovalGate;
import io.github.nephets.agentswitchyard.core.executor.FlowExecutor;
import io.github.nephets.agentswitchyard.sample.ledger.Ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 信贷审批助手 demo：把可靠性层三件事（企业审批 / 补偿事务 / 确定性回放）串成一个业务闭环。 */
@SpringBootApplication
@RestController
public class SampleApplication {

    private static final Logger LOG = LoggerFactory.getLogger(SampleApplication.class);

    @Autowired private FlowExecutor flowExecutor;

    @Autowired private ApprovalGate approvalGate;

    @Autowired private Ledger ledger;

    /**
     * 应用入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(SampleApplication.class, args);
        LOG.info("信贷审批助手 demo 已启动（/_switchyard 运维端点 + /demo 业务端点）");
    }

    /**
     * demo 内置确定性模型（默认启用；设 {@code spring.ai.model.chat=openai} 切真实模型， 本 bean 随条件自动让位 —— CI 全程零真实 API
     * 调用）。
     *
     * <p>必须是 {@code static}：starter 的 {@code flowExecutor → chatGateway → ChatModel}
     * 依赖链会在本类实例化完成前（字段注入 flowExecutor 时）反向需要本 bean， 非 static 工厂方法会形成不可解的 bean 创建环。
     */
    @Bean
    @ConditionalOnProperty(
            name = "spring.ai.model.chat",
            havingValue = "demo",
            matchIfMissing = true)
    public static org.springframework.ai.chat.model.ChatModel demoChatModel() {
        return new DemoChatModel();
    }

    // ==================== demo 便捷端点（薄壳，直通 starter 的 /_switchyard 能力） ====================

    /** demo 健康 + 指引。 */
    @GetMapping("/demo/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "flow", "credit-approve",
                "hint", "POST /demo/flow/start {\"ref\":\"R-1\",\"amount\":500000}");
    }

    /** 启动一笔授信申请。 */
    @PostMapping("/demo/flow/start")
    public Map<String, Object> start(@RequestBody Map<String, Object> input) {
        String instanceId =
                flowExecutor.start("credit-approve", input, (String) input.get("tenantId"));
        return Map.of("flowInstanceId", instanceId, "state", "PENDING");
    }

    /** 查实例（含步骤明细）。 */
    @GetMapping("/demo/flow/{instanceId}")
    public Map<String, Object> instance(@PathVariable String instanceId) {
        return Map.of(
                "instance",
                flowExecutor.instance(instanceId),
                "steps",
                flowExecutor.steps(instanceId));
    }

    /** 副作用台账（验收观察窗口）。 */
    @GetMapping("/demo/ledger/{instanceId}")
    public List<Map<String, Object>> ledger(@PathVariable String instanceId) {
        return ledger.rows(instanceId);
    }

    /** 待审批列表（按角色）。 */
    @GetMapping("/demo/approvals")
    public List<?> approvals(@RequestParam(required = false) String role) {
        return approvalGate.listPending(role);
    }
}
