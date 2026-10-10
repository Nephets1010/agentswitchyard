/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.approval;

import java.time.Instant;

/**
 * 审批记录（完整留痕：谁、何时、以什么身份、基于什么理由、批了还是拒了）。
 *
 * <p>「审批意见留痕」是本仓相对 LangGraph4j interrupt 的差异化能力之一（架构 §5.0）。
 *
 * @param approvalId 审批 ID（幂等续跑的锚点）
 * @param flowInstanceId 流程实例 ID
 * @param stepId 步骤 ID
 * @param approverRole 要求的审批角色
 * @param state 审批状态
 * @param approver 实际审批人（人工审批为用户标识；超时自动决策为 {@code system-timeout}）
 * @param note 审批意见（完整留痕）
 * @param reason 挂起原因
 * @param degraded 是否降级通过（AUTO_DEGRADE 超时或显式降级）
 * @param requestedAt 挂起时间
 * @param decidedAt 决策时间
 * @param dueAt 到期时间（HOLD_FOREVER 为 null）
 * @param timeoutPolicy 超时策略
 */
public record ApprovalRecord(
        String approvalId,
        String flowInstanceId,
        String stepId,
        String approverRole,
        ApprovalState state,
        String approver,
        String note,
        String reason,
        boolean degraded,
        Instant requestedAt,
        Instant decidedAt,
        Instant dueAt,
        ApprovalTimeoutPolicy timeoutPolicy) {

    /** 紧凑构造器：fail-fast 校验关键标识。 */
    public ApprovalRecord {
        if (approvalId == null || approvalId.isBlank()) {
            throw new IllegalArgumentException("approvalId 不可为空");
        }
        if (flowInstanceId == null || flowInstanceId.isBlank()) {
            throw new IllegalArgumentException("审批 " + approvalId + " 缺少 flowInstanceId");
        }
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("审批 " + approvalId + " 缺少 stepId");
        }
        if (approverRole == null || approverRole.isBlank()) {
            throw new IllegalArgumentException(
                    "审批 " + approvalId + " 缺少 approverRole（无权限模型的审批等于没审批）");
        }
        if (state == null) {
            state = ApprovalState.PENDING;
        }
        if (timeoutPolicy == null) {
            timeoutPolicy = ApprovalTimeoutPolicy.HOLD_FOREVER;
        }
    }

    /** 派生：人工决策（仅当 PENDING）。 */
    public ApprovalRecord decided(ApprovalState target, String approver, String note, Instant at) {
        return new ApprovalRecord(
                approvalId,
                flowInstanceId,
                stepId,
                approverRole,
                target,
                approver,
                note,
                reason,
                false,
                requestedAt,
                at,
                dueAt,
                timeoutPolicy);
    }

    /** 派生：超时自动决策（审批人记为 system-timeout，degraded 按策略）。 */
    public ApprovalRecord timedOut(boolean approved, Instant at) {
        return new ApprovalRecord(
                approvalId,
                flowInstanceId,
                stepId,
                approverRole,
                ApprovalState.TIMEOUT,
                "system-timeout",
                approved
                        ? "超时自动"
                                + (this.timeoutPolicy == ApprovalTimeoutPolicy.AUTO_DEGRADE
                                        ? "降级"
                                        : "")
                                + "通过"
                        : "超时自动拒绝",
                reason,
                approved && this.timeoutPolicy == ApprovalTimeoutPolicy.AUTO_DEGRADE,
                requestedAt,
                at,
                dueAt,
                timeoutPolicy);
    }
}
