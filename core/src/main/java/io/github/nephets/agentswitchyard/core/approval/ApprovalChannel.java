/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.approval;

/**
 * 审批通知渠道（SPI）：挂起发生时把待办推出去。
 *
 * <p>首版内置 {@link WebhookApprovalChannel}（企业审批渠道钉钉/企微/邮件的对接方 只要订阅这个 webhook 就能收到待办）；直连渠道放 P1（架构
 * §5.1）。
 */
@FunctionalInterface
public interface ApprovalChannel {

    /**
     * 推送审批待办。
     *
     * @param record 新挂起的审批记录
     */
    void notify(ApprovalRecord record);
}
