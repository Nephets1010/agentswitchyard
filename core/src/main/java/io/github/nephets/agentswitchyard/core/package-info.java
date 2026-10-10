/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
/**
 * agentswitchyard core：纯逻辑层。
 *
 * <p>本包是 LangGraph4j 的可靠性层的核心实现（ADR-0002），包含：
 *
 * <ul>
 *   <li>{@code definition}：流程定义（Java DSL + YAML 双入口）
 *   <li>{@code instance}：执行实例、步骤记录与单步执行器（虚拟线程）
 *   <li>{@code executor}：流程执行引擎（启动 / 恢复 / 审批回调 / 回放）
 *   <li>{@code idempotency}：幂等键判定
 *   <li>{@code retry}：错误分类与重试
 *   <li>{@code compensation}：Saga 式反向补偿
 *   <li>{@code approval}：Human-in-the-loop 审批门
 *   <li>{@code replay}：确定性回放（响应录制 + 注入）
 *   <li>{@code tool}：工具注册与沙箱三道闸
 *   <li>{@code protocol}：多 Agent 消息协议（v1）
 *   <li>{@code trace}：执行轨迹导出（JSONL / Markdown / SPN-001 span 属性）
 *   <li>{@code store}：StepStore SPI（JDBC 实现在 agentswitchyard-store 模块）
 *   <li>{@code trace/usage/pricing/audit}：跨仓契约（CTX/USG/PRC/EVT/ERR-001）
 * </ul>
 *
 * <p>★ 依赖边界（ADR-0001）：除 Spring AI 的 {@code ChatModel} SPI（ReplayChatModel 需要）外， 本模块<b>零 Spring
 * 依赖</b>——没有 Spring Boot、没有 Web、没有 Servlet。enforcer 在构建期强制。
 */
package io.github.nephets.agentswitchyard.core;
