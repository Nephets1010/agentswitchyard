/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.sample.tools;

import io.github.nephets.agentswitchyard.core.tool.RiskLevel;
import io.github.nephets.agentswitchyard.core.tool.SwitchyardTool;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 示例工具（注册期治理三道闸的展示）：
 *
 * <ul>
 *   <li>kb_lookup：低危、禁止全部外呼（allowedDomains 空 = deny-all）
 *   <li>ticket_create：中危、允许内部工单域名、无需审批
 * </ul>
 */
@Component
public class DemoTools {

    /** 知识库检索（demo：纯内存查询，零外呼）。 */
    @SwitchyardTool(
            toolName = "kb_lookup",
            description = "信贷政策知识库检索",
            riskLevel = RiskLevel.LOW,
            allowedDomains = {}) // 空 = 禁止全部外呼（第二道闸）
    public Map<String, Object> lookup(String keyword) {
        return Map.of("keyword", keyword, "hit", "政策 Q3：单笔 100 万以上需人工复核");
    }

    /** 创建工单（demo：不真发请求）。 */
    @SwitchyardTool(
            toolName = "ticket_create",
            description = "风控工单系统建单",
            riskLevel = RiskLevel.MEDIUM,
            requiresApproval = false,
            allowedDomains = {"tickets.internal.example.com"})
    public Map<String, Object> createTicket(String subject) {
        return Map.of("ticketId", "T-" + System.currentTimeMillis() % 100000, "subject", subject);
    }
}
