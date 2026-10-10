/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.retry;

/**
 * 错误分类器（SPI）：判定异常是否可重试。
 *
 * <p>重试的可判定性是硬约束（架构 §8.4）：只有「重试可能成功」的错误才允许重试 —— 超时 / 429 / 5xx 可以重试；业务校验失败、参数错误重试一万次也不会成功，
 * 只会让副作用风险翻倍。
 */
@FunctionalInterface
public interface ErrorClassifier {

    /**
     * 判定异常是否可重试。
     *
     * @param throwable 业务执行抛出的异常
     * @return true 表示可重试（超时 / 上游暂时不可用等）
     */
    boolean isRetryable(Throwable throwable);
}
