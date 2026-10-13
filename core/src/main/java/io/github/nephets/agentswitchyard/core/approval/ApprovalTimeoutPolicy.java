/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.approval;

/**
 * 审批超时策略（架构 §5.0 的核心卖点之一：LangGraph4j 有 interrupt，但没有超时语义）。
 *
 * <p>超时到点后的行为：
 *
 * <ul>
 *   <li>{@link #AUTO_APPROVE}：自动通过（审批人记为 {@code system-timeout}）
 *   <li>{@link #AUTO_REJECT}：自动拒绝（流程进入补偿）
 *   <li>{@link #AUTO_DEGRADE}：自动「降级通过」（通过但留降级标记，下游可走更保守路径）
 *   <li>{@link #HOLD_FOREVER}：永不超时（dueAt 置 null，等待人工，直到有人处理）
 * </ul>
 */
public enum ApprovalTimeoutPolicy {

    /** 超时自动通过。 */
    AUTO_APPROVE,

    /** 超时自动拒绝。 */
    AUTO_REJECT,

    /** 超时自动降级通过。 */
    AUTO_DEGRADE,

    /** 永不超时。 */
    HOLD_FOREVER;

    /** 超时后是否视为「通过」。 */
    public boolean approvedOnTimeout() {
        return this == AUTO_APPROVE || this == AUTO_DEGRADE;
    }
}
