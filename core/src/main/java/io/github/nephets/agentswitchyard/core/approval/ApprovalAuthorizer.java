/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.approval;

import io.github.nephets.agentswitchyard.core.ErrorCode;
import io.github.nephets.agentswitchyard.core.SwitchyardException;

/**
 * 审批授权校验（SPI）。
 *
 * <p>★ 「非授权角色无法审批」是硬验收标准。首版不强绑 Spring Security（金融客户常有自研权限体系）， 用本 SPI 隔离（架构 Q-E）；对接 Spring Security
 * authorities 的适配器放 P1。
 */
@FunctionalInterface
public interface ApprovalAuthorizer {

    /**
     * 校验审批人是否有权审批。
     *
     * @param record 待审批记录（含要求的角色 approverRole）
     * @param decision 决策（含审批人声称的角色）
     * @throws SwitchyardException 角色不符时抛 {@link ErrorCode#SW_APPROVAL_DENIED}
     */
    void authorize(ApprovalRecord record, ApprovalDecision decision);
}
