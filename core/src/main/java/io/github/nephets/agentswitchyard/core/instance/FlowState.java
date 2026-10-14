/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.instance;

import java.util.Set;

/**
 * 流程实例状态机（只有 6 类状态，不做图 —— ADR-0002）。
 *
 * <pre>
 * PENDING → RUNNING → SUCCEEDED
 *    │         │
 *    │         ├──→ WAITING_APPROVAL ──(approve)──→ RUNNING
 *    │         │              └──(reject/timeout)──→ REJECTED → COMPENSATING
 *    │         ├──→ RETRYING → RUNNING
 *    │         └──→ FAILED → COMPENSATING → COMPENSATED
 *    │                              └──→ COMPENSATE_FAILED（人工介入告警）
 *    └──→ CANCELLED
 * </pre>
 *
 * <p>★ 状态机守卫的价值：非法转移（如 PENDING → COMPENSATING）在写入前就抛异常， 而不是让一个错乱的状态悄悄写进数据库，两天后有人在审计报告里发现它。
 */
public enum FlowState {

    /** 已创建，尚未开始执行。 */
    PENDING,

    /** 执行中（含从审批恢复 / 重试恢复后的继续执行）。 */
    RUNNING,

    /** 挂起等待人工审批。进程重启后仍保持挂起态（审批记录已持久化）。 */
    WAITING_APPROVAL,

    /** 某步失败，正在按退避参数重试。 */
    RETRYING,

    /** 全部步骤成功。 */
    SUCCEEDED,

    /** 审批拒绝（补偿随后执行）。 */
    REJECTED,

    /** 某步最终失败，正在反向补偿。 */
    FAILED,

    /** 补偿执行中。 */
    COMPENSATING,

    /** 补偿全部成功（流程以「已撤销」终态关闭）。 */
    COMPENSATED,

    /** 补偿失败 —— 需要人工介入，这是最严重的终态。 */
    COMPENSATE_FAILED,

    /** 主动取消。 */
    CANCELLED;

    /**
     * 允许的后继状态集（状态机的唯一事实来源；用 switch 而非 Map.of —— 11 个状态超过 Map.of 的 10 对上限）。
     *
     * @return 允许转移的目标状态集合
     */
    public java.util.Set<FlowState> allowedTargets() {
        return switch (this) {
            case PENDING -> Set.of(RUNNING, CANCELLED);
            case RUNNING ->
                    Set.of(SUCCEEDED, WAITING_APPROVAL, RETRYING, FAILED, COMPENSATING, CANCELLED);
            case WAITING_APPROVAL -> Set.of(RUNNING, REJECTED, COMPENSATING, CANCELLED);
            case RETRYING -> Set.of(RUNNING, FAILED, COMPENSATING, CANCELLED);
            case FAILED, REJECTED -> Set.of(COMPENSATING);
            case COMPENSATING -> Set.of(COMPENSATED, COMPENSATE_FAILED);
            default -> Set.of(); // 终态：SUCCEEDED / COMPENSATED / COMPENSATE_FAILED / CANCELLED
        };
    }

    /**
     * 校验并执行转移；非法转移抛 {@link IllegalStateException}。
     *
     * @param target 目标状态
     * @return 目标状态（便于链式书写）
     */
    public FlowState transitionTo(FlowState target) {
        if (target == null || !allowedTargets().contains(target)) {
            throw new IllegalStateException("非法的流程状态转移：" + this + " → " + target);
        }
        return target;
    }

    /** 是否终态。 */
    public boolean terminal() {
        return allowedTargets().isEmpty();
    }

    /** 是否处于补偿阶段。 */
    public boolean compensating() {
        return this == COMPENSATING || this == COMPENSATED || this == COMPENSATE_FAILED;
    }
}
