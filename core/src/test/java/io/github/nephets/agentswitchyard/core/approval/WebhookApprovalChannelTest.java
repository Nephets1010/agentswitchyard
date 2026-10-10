/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.approval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;

/** WebhookApprovalChannel：未配置回调地址时必须走「仅留痕」降级，不允许抛异常打断挂起。 */
@DisplayName("WebhookApprovalChannel（降级安全）")
class WebhookApprovalChannelTest {

    private static ApprovalRecord record() {
        return new ApprovalRecord(
                "appr-ch-1",
                "inst-ch-1",
                "manual-review",
                "risk-officer",
                ApprovalState.PENDING,
                null,
                null,
                "人工复核",
                false,
                Instant.parse("2026-10-10T08:00:00Z"),
                null,
                null,
                ApprovalTimeoutPolicy.HOLD_FOREVER);
    }

    @Test
    @DisplayName("无 webhook 配置：notify 仅留痕（log-only 降级，不抛异常）")
    void notifyWithoutUrlIsSafe() {
        WebhookApprovalChannel channel = new WebhookApprovalChannel();
        assertThatCode(() -> channel.notify(record())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("非法 webhook 地址：降级为留痕并记录，不抛异常打断挂起路径")
    void notifyWithInvalidUrlDoesNotBreakSuspend() {
        WebhookApprovalChannel channel =
                new WebhookApprovalChannel("http://127.0.0.1:1/no-such-hook");
        assertThatCode(() -> channel.notify(record())).doesNotThrowAnyException();
    }
}
