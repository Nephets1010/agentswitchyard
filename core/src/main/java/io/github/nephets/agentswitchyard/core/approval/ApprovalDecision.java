/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.approval;

import java.time.Instant;

/**
 * 审批决策（审批人提交的动作）。
 *
 * @param approver 审批人标识
 * @param approverRole 审批人声称的角色（由 ApprovalAuthorizer 校验）
 * @param approved true=通过，false=拒绝
 * @param note 审批意见（完整留痕）
 * @param degraded 是否降级通过
 * @param decidedAt 决策时间
 */
public record ApprovalDecision(
        String approver,
        String approverRole,
        boolean approved,
        String note,
        boolean degraded,
        Instant decidedAt) {

    /** 紧凑构造器：fail-fast 校验。 */
    public ApprovalDecision {
        if (approver == null || approver.isBlank()) {
            throw new IllegalArgumentException("approver 不可为空（审批必须留痕到人）");
        }
        if (approverRole == null || approverRole.isBlank()) {
            throw new IllegalArgumentException("approverRole 不可为空");
        }
    }
}
