/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.instance;

/**
 * 步骤级状态（比流程状态细一层的账本）。
 *
 * <p>崩溃恢复的判定依据：{@code SUCCEEDED} 的步骤在续跑时被直接跳过 ——「不重复副作用」的全部含义都在这个判定里。
 */
public enum StepState {

    /** 已登记，未开始。 */
    PENDING,

    /** 执行中（tryBeginStep 的原子插入产生该状态）。 */
    RUNNING,

    /** 执行成功（输出与计量已落库）。 */
    SUCCEEDED,

    /** 挂起等待人工审批。 */
    WAITING_APPROVAL,

    /** 最终失败（重试耗尽）。 */
    FAILED,

    /** 补偿执行中。 */
    COMPENSATING,

    /** 补偿成功（该步骤的副作用已被反向撤销）。 */
    COMPENSATED,

    /** 补偿失败（需要人工介入）。 */
    COMPENSATE_FAILED,

    /** 回放/恢复时被跳过。 */
    SKIPPED,

    /** 主动取消。 */
    CANCELLED;

    /** 是否已成功完成。 */
    public boolean succeeded() {
        return this == SUCCEEDED;
    }

    /** 是否已补偿。 */
    public boolean compensated() {
        return this == COMPENSATED;
    }
}
