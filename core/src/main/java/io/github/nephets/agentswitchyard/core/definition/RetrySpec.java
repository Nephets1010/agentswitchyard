/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.definition;

/**
 * 重试参数（指数退避）。
 *
 * <p>只对 {@code retryable=true} 的错误重试（见 {@code ErrorClassifier}）： 业务校验失败 / 参数错误重试多少次都不会成功，只会放大副作用风险。
 *
 * @param maxAttempts 最大尝试次数（含首次，最小 1）
 * @param initialBackoffMs 首次重试前的退避毫秒数
 * @param maxBackoffMs 退避上限毫秒数
 * @param multiplier 退避乘数（&ge; 1.0）
 */
public record RetrySpec(
        int maxAttempts, long initialBackoffMs, long maxBackoffMs, double multiplier) {

    /** 默认：不重试（企业流程里「重试」应该是显式决策而不是默认行为）。 */
    public static final RetrySpec NO_RETRY = new RetrySpec(1, 0, 0, 1.0);

    /** 稳妥默认：3 次、100ms 起步、2 倍退避、上限 5s。 */
    public static final RetrySpec DEFAULT = new RetrySpec(3, 100, 5_000, 2.0);

    /** 紧凑构造器：规整非法值而不是抛异常（声明式参数，宽松规整 + 保守下界）。 */
    public RetrySpec {
        maxAttempts = Math.max(1, maxAttempts);
        initialBackoffMs = Math.max(0, initialBackoffMs);
        maxBackoffMs = Math.max(initialBackoffMs, maxBackoffMs);
        multiplier = Math.max(1.0, multiplier);
    }

    /**
     * 第 {@code attempt} 次失败后的退避毫秒数（attempt 从 1 计）。
     *
     * @param attempt 已失败的次数
     * @return 退避毫秒数，不超过 {@code maxBackoffMs}
     */
    public long backoffMs(int attempt) {
        if (attempt < 1) {
            return 0;
        }
        double backoff = initialBackoffMs * Math.pow(multiplier, attempt - 1);
        return (long) Math.min(maxBackoffMs, Math.max(0, backoff));
    }

    /** 派生一个只改最大尝试次数的 RetrySpec（YAML 加载用）。 */
    public RetrySpec withAttempts(int attempts) {
        return new RetrySpec(attempts, initialBackoffMs, maxBackoffMs, multiplier);
    }
}
