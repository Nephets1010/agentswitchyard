/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.retry;

import io.github.nephets.agentswitchyard.core.SwitchyardException;

import java.util.concurrent.TimeoutException;

/**
 * 默认错误分类器。
 *
 * <p>规则：
 *
 * <ul>
 *   <li>{@link SwitchyardException}：由其 {@code ErrorCode.retryable} 决定（ERR-001 契约）
 *   <li>{@link TimeoutException} / {@link java.util.concurrent.TimeoutException}：可重试
 *   <li>{@code java.io.IOException} / {@code java.net.ConnectException}：可重试（网络抖动）
 *   <li>其余（NPE、业务校验失败、IllegalArgument…）：不可重试
 * </ul>
 */
public final class DefaultErrorClassifier implements ErrorClassifier {

    /** 共享实例（无状态）。 */
    public static final DefaultErrorClassifier INSTANCE = new DefaultErrorClassifier();

    @Override
    public boolean isRetryable(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        if (throwable instanceof SwitchyardException switchyardEx) {
            return switchyardEx.errorCode().retryable();
        }
        if (throwable instanceof TimeoutException) {
            return true;
        }
        if (throwable instanceof java.io.IOException) {
            // 连接被拒 / 读超时 / 连接重置等网络层错误按「上游暂时不可用」处理
            return true;
        }
        return false;
    }
}
