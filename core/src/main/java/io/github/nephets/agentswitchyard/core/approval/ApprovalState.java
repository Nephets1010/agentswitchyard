/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.approval;

/** 审批状态（契约 SPN-001：{@code swy.approval.state} 的取值，跨三仓字面一致）。 */
public enum ApprovalState {

    /** 待审批。 */
    PENDING,

    /** 已通过。 */
    APPROVED,

    /** 已拒绝。 */
    REJECTED,

    /** 超时（后续按超时策略自动通过 / 拒绝 / 降级）。 */
    TIMEOUT;

    /** 是否已出结果（终态）。 */
    public boolean decided() {
        return this != PENDING;
    }
}
