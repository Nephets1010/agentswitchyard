/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具注册注解：标注在 bean 的方法上，由 starter 的 {@code ToolRegisteringPostProcessor} 在容器启动时扫描并注册到 {@link
 * ToolRegistry}。
 *
 * <p>★ 高危工具（{@code HIGH/CRITICAL}）不声明 {@code requiresApproval = true} 时，
 * <b>容器启动即失败</b>（注册期拒绝，不是第一次调用才报错）。
 *
 * <p>示例：
 *
 * <pre>{@code
 * @SwitchyardTool(toolName = "kb_lookup", riskLevel = RiskLevel.LOW)
 * public String lookup(String keyword) { ... }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SwitchyardTool {

    /** 工具名（Agent 可调用的唯一标识）。 */
    String toolName();

    /** 人类可读说明。 */
    String description() default "";

    /** 风险等级。 */
    RiskLevel riskLevel() default RiskLevel.LOW;

    /** 调用前是否需要人工审批（高危工具必须为 true）。 */
    boolean requiresApproval() default false;

    /** 允许访问的域名白名单；空 = 禁止全部外呼。 */
    String[] allowedDomains() default {};

    /** 单次调用超时毫秒。 */
    long timeoutMs() default 10_000;

    /** 最大重试次数。 */
    int maxRetries() default 0;

    /** 是否幂等。 */
    boolean idempotent() default true;
}
