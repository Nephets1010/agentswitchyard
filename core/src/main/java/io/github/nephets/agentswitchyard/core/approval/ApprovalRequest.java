/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.approval;

import java.time.Duration;
import java.time.Instant;

/**
 * 审批请求（挂起时创建）。
 *
 * @param flowInstanceId 流程实例 ID
 * @param stepId 步骤 ID
 * @param approverRole 审批人角色（由 ApprovalAuthorizer 校验）
 * @param reason 挂起原因（如「规则评分 0.82 &gt; 阈值 0.75」，进审批留痕）
 * @param timeoutPolicy 超时策略
 * @param timeout 超时时长（HOLD_FOREVER 时忽略）
 * @param requestedAt 挂起时间
 */
public record ApprovalRequest(
        String flowInstanceId,
        String stepId,
        String approverRole,
        String reason,
        ApprovalTimeoutPolicy timeoutPolicy,
        Duration timeout,
        Instant requestedAt) {

    /** 紧凑构造器：null 归一。 */
    public ApprovalRequest {
        if (timeoutPolicy == null) {
            timeoutPolicy = ApprovalTimeoutPolicy.HOLD_FOREVER;
        }
        if (timeout == null) {
            timeout = Duration.ZERO;
        }
    }

    /**
     * 计算到期时间；{@code HOLD_FOREVER} 返回 null（永不超时）。
     *
     * @return 到期时间；永不超时为 null
     */
    public Instant dueAt() {
        if (timeoutPolicy == ApprovalTimeoutPolicy.HOLD_FOREVER
                || timeout.isZero()
                || timeout.isNegative()) {
            return null;
        }
        Instant base = requestedAt == null ? Instant.now() : requestedAt;
        return base.plus(timeout);
    }
}
