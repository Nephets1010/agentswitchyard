/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.tool;

import java.time.Duration;
import java.util.Set;

/**
 * 工具描述符：注册即声明，全部安全属性显式化。
 *
 * <p>★ 两条注册期硬规则（在紧凑构造器里 fail-fast）：
 *
 * <ol>
 *   <li>{@code HIGH/CRITICAL} 且 {@code requiresApproval=false} → <b>注册即拒绝</b>
 *   <li>{@code allowedDomains} 默认<b>空集合</b> = 禁止全部外呼（默认拒绝，不是默认放行）
 * </ol>
 *
 * @param toolName 工具名（Agent 可调用的唯一标识；未注册不可调）
 * @param description 人类可读说明
 * @param timeout 单次调用超时
 * @param maxRetries 最大重试次数
 * @param idempotent 是否幂等（非幂等工具不重试，避免副作用翻倍）
 * @param requiresApproval 调用前是否需要人工审批
 * @param allowedDomains 允许访问的域名白名单；<b>空集合 = 禁止全部外呼</b>
 * @param riskLevel 风险等级
 */
public record ToolDescriptor(
        String toolName,
        String description,
        Duration timeout,
        int maxRetries,
        boolean idempotent,
        boolean requiresApproval,
        Set<String> allowedDomains,
        RiskLevel riskLevel) {

    /** 紧凑构造器：注册期安全闸（fail-fast）。 */
    public ToolDescriptor {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName 不可为空");
        }
        if (riskLevel == null) {
            riskLevel = RiskLevel.LOW;
        }
        // ★ 第三道闸：高危工具没有审批声明，注册时即拒绝（不是运行时）
        if (riskLevel.requiresApprovalDeclaration() && !requiresApproval) {
            throw new IllegalArgumentException(
                    "工具 "
                            + toolName
                            + " 的 riskLevel="
                            + riskLevel
                            + " 属高危工具，必须声明 requiresApproval=true（注册期拒绝，见架构风险 6 对策）");
        }
        timeout =
                timeout == null || timeout.isZero() || timeout.isNegative()
                        ? Duration.ofSeconds(10)
                        : timeout;
        maxRetries = Math.max(0, maxRetries);
        allowedDomains = allowedDomains == null ? Set.of() : Set.copyOf(allowedDomains);
    }

    /** 便捷工厂：低危只读工具。 */
    public static ToolDescriptor readOnly(String toolName, String description) {
        return new ToolDescriptor(
                toolName,
                description,
                Duration.ofSeconds(10),
                0,
                true,
                false,
                Set.of(),
                RiskLevel.LOW);
    }
}
