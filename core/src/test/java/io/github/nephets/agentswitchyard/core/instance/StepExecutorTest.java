/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.instance;

import io.github.nephets.agentswitchyard.core.definition.RetrySpec;
import io.github.nephets.agentswitchyard.core.definition.StepDefinition;
import io.github.nephets.agentswitchyard.core.definition.StepHandler;
import io.github.nephets.agentswitchyard.core.retry.DefaultErrorClassifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 单步执行器：超时 + 指数退避重试 + 不可重试错误直接抛。 */
@DisplayName("StepExecutor（虚拟线程 + 超时 + 重试）")
class StepExecutorTest {

    private final StepExecutor executor = new StepExecutor(new DefaultErrorClassifier());

    private StepDefinition step(StepHandler handlerIgnoring, int maxAttempts, Duration timeout) {
        return new StepDefinition(
                "s1",
                io.github.nephets.agentswitchyard.core.definition.StepKind.RULE,
                0,
                null,
                false,
                null,
                timeout,
                maxAttempts > 1 ? RetrySpec.DEFAULT.withAttempts(maxAttempts) : RetrySpec.NO_RETRY,
                null,
                null);
    }

    private StepContext context() {
        return new StepContext(
                "inst-1", "flow-1", "s1", 0, "trace", "span", Map.of("ref", "R"), Map.of(), null);
    }

    @Test
    @DisplayName("成功路径返回结果")
    void success() throws Exception {
        StepDefinition step = step(null, 1, Duration.ZERO);
        StepResult result =
                executor.execute(step, ctx -> StepResult.ofRule(Map.of("ok", true)), context());
        assertThat(result.output()).containsEntry("ok", true);
    }

    @Test
    @DisplayName("超时步骤抛 TimeoutException（虚拟线程被中断）")
    void timeoutThrows() {
        StepDefinition step = step(null, 1, Duration.ofMillis(80));
        long start = System.nanoTime();
        assertThatThrownBy(
                        () ->
                                executor.execute(
                                        step,
                                        ctx -> {
                                            Thread.sleep(5_000);
                                            return StepResult.ofRule(Map.of());
                                        },
                                        context()))
                .isInstanceOf(TimeoutException.class)
                .hasMessageContaining("超时");
        // 必须在超时附近返回，而不是等满 5 秒
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isLessThan(2_000);
    }

    @Test
    @DisplayName("可重试错误按 RetrySpec 重试并最终成功")
    void retryableRetriedUntilSuccess() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        StepDefinition step =
                new StepDefinition(
                        "s1",
                        io.github.nephets.agentswitchyard.core.definition.StepKind.RULE,
                        0,
                        null,
                        false,
                        null,
                        Duration.ZERO,
                        new RetrySpec(3, 1, 5, 1.0),
                        null,
                        null);
        StepResult result =
                executor.execute(
                        step,
                        ctx -> {
                            if (attempts.incrementAndGet() < 3) {
                                throw new java.io.IOException("网络抖动（可重试）");
                            }
                            return StepResult.ofRule(Map.of("attempt", attempts.get()));
                        },
                        context());
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(result.output()).containsEntry("attempt", 3);
    }

    @Test
    @DisplayName("不可重试错误（业务校验失败）不重试，原始异常直接抛出")
    void nonRetryableNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        StepDefinition step = step(null, 5, Duration.ZERO);
        assertThatThrownBy(
                        () ->
                                executor.execute(
                                        step,
                                        ctx -> {
                                            attempts.incrementAndGet();
                                            throw new IllegalArgumentException("参数非法，重试无意义");
                                        },
                                        context()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("参数非法");
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("重试耗尽后抛最后一次的原始异常")
    void retryExhaustedThrowsLastException() {
        StepDefinition step =
                new StepDefinition(
                        "s1",
                        io.github.nephets.agentswitchyard.core.definition.StepKind.RULE,
                        0,
                        null,
                        false,
                        null,
                        Duration.ZERO,
                        new RetrySpec(2, 1, 1, 1.0),
                        null,
                        null);
        assertThatThrownBy(
                        () ->
                                executor.execute(
                                        step,
                                        ctx -> {
                                            throw new java.io.IOException("一直挂");
                                        },
                                        context()))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("一直挂");
    }
}
