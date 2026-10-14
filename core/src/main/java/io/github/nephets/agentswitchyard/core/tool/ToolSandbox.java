/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.tool;

import io.github.nephets.agentswitchyard.core.ErrorCode;
import io.github.nephets.agentswitchyard.core.SwitchyardException;

import java.net.URI;

/**
 * 工具沙箱：调用前的三道闸（注册闸 / 域名闸 / 审批声明闸）。
 *
 * <p>★ 诚实定位（架构风险 6）：这是「对抗 Prompt 注入的第一道闸」， 不是字节码级沙箱 —— 完整沙箱在 P1，README 必须写清边界，不夸大。
 *
 * <p>三道闸：
 *
 * <ol>
 *   <li><b>注册闸</b>：未注册工具不可被调用（{@code SW_TOOL_DENIED}）
 *   <li><b>域名闸</b>：{@code allowedDomains} 为空集合 = 禁止全部外呼； 非空时目标域名必须与白名单精确匹配或以其子域形式匹配
 *   <li><b>审批声明闸</b>：{@code HIGH/CRITICAL} 且未声明 {@code requiresApproval} 的工具 在注册时即拒绝（ToolDescriptor
 *       构造器），本类复核兜底
 * </ol>
 */
public final class ToolSandbox {

    private final ToolRegistry registry;

    /** 创建沙箱。 */
    public ToolSandbox(ToolRegistry registry) {
        this.registry = registry;
    }

    /**
     * 第一道闸：工具必须显式注册。
     *
     * @param toolName 工具名
     * @return 注册的描述符
     * @throws SwitchyardException 未注册（SW_TOOL_DENIED）
     */
    public ToolDescriptor checkRegistered(String toolName) {
        return registry.find(toolName)
                .orElseThrow(
                        () ->
                                new SwitchyardException(
                                        ErrorCode.SW_TOOL_DENIED,
                                        "工具 [" + toolName + "] 未注册（Agent 只能调用显式注册的工具）",
                                        null));
    }

    /**
     * 第二道闸：域名白名单。
     *
     * <p>规则：目标为空（本地调用）放行；白名单为空集合 = <b>禁止全部外呼</b>； 否则目标 host 必须等于白名单某项或是其子域。
     *
     * @param descriptor 工具描述符
     * @param targetUrl 目标 URL（本地调用传 null）
     * @throws SwitchyardException 超出白名单（SW_TOOL_DENIED）
     */
    public void checkDomain(ToolDescriptor descriptor, String targetUrl) {
        if (targetUrl == null || targetUrl.isBlank()) {
            return; // 本地 / 进程内调用
        }
        if (descriptor.allowedDomains().isEmpty()) {
            throw new SwitchyardException(
                    ErrorCode.SW_TOOL_DENIED,
                    "工具 [" + descriptor.toolName() + "] 未配置 allowedDomains —— 默认禁止全部外呼（默认拒绝策略）",
                    null);
        }
        String host;
        try {
            host = URI.create(targetUrl).getHost();
        } catch (IllegalArgumentException ex) {
            throw new SwitchyardException(
                    ErrorCode.SW_TOOL_DENIED,
                    "工具 [" + descriptor.toolName() + "] 的目标 URL 非法: " + targetUrl,
                    null);
        }
        if (host == null) {
            throw new SwitchyardException(
                    ErrorCode.SW_TOOL_DENIED,
                    "工具 [" + descriptor.toolName() + "] 的目标 URL 无 host: " + targetUrl,
                    null);
        }
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        boolean allowed =
                descriptor.allowedDomains().stream()
                        .anyMatch(
                                domain -> {
                                    String d = domain.toLowerCase(java.util.Locale.ROOT).trim();
                                    return normalized.equals(d) || normalized.endsWith("." + d);
                                });
        if (!allowed) {
            throw new SwitchyardException(
                    ErrorCode.SW_TOOL_DENIED,
                    "工具 ["
                            + descriptor.toolName()
                            + "] 请求访问 ["
                            + host
                            + "]，超出白名单 "
                            + descriptor.allowedDomains(),
                    null);
        }
    }

    /**
     * 第三道闸：审批声明复核（注册期已拒绝，这里兜底防反射绕过构造器）。
     *
     * @param descriptor 工具描述符
     * @throws SwitchyardException 高危未声明审批（SW_TOOL_DENIED）
     */
    public void checkApprovalDeclared(ToolDescriptor descriptor) {
        if (descriptor.riskLevel().requiresApprovalDeclaration()
                && !descriptor.requiresApproval()) {
            throw new SwitchyardException(
                    ErrorCode.SW_TOOL_DENIED,
                    "工具 ["
                            + descriptor.toolName()
                            + "] riskLevel="
                            + descriptor.riskLevel()
                            + " 却未声明 requiresApproval=true",
                    null);
        }
    }

    /**
     * 三道闸一次过（调用前统一入口）。
     *
     * @param toolName 工具名
     * @param targetUrl 目标 URL（本地调用传 null）
     * @return 通过全部检查的描述符
     */
    public ToolDescriptor guard(String toolName, String targetUrl) {
        ToolDescriptor descriptor = checkRegistered(toolName);
        checkDomain(descriptor, targetUrl);
        checkApprovalDeclared(descriptor);
        return descriptor;
    }
}
