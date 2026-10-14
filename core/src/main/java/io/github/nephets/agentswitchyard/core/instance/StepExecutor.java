/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.instance;

import io.github.nephets.agentswitchyard.core.definition.StepDefinition;
import io.github.nephets.agentswitchyard.core.definition.StepHandler;
import io.github.nephets.agentswitchyard.core.retry.ErrorClassifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 单步执行器：超时 + 重试，跑在虚拟线程上。
 *
 * <p>★ 为什么每步一个虚拟线程：HITL 挂起、审批等待、重试退避都是「等」， 虚拟线程让这些等待的成本接近于零（{@code Thread.sleep} 在虚拟线程上不占平台线程）。
 * {@code StructuredTaskScope} 仍是预览 API，封装在本类内部，未来替换不影响上层（架构 §5.1）。
 */
public final class StepExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(StepExecutor.class);

    private final ErrorClassifier errorClassifier;

    /** 创建单步执行器。 */
    public StepExecutor(ErrorClassifier errorClassifier) {
        this.errorClassifier = errorClassifier;
    }

    /**
     * 执行单步（带超时与指数退避重试）。
     *
     * @param step 步骤定义（超时 + 重试参数来源）
     * @param handler 业务处理器
     * @param context 步骤上下文
     * @return 执行结果
     * @throws Exception 最终失败（重试耗尽或不可重试）时抛出<b>最后一次</b>的业务异常
     */
    public StepResult execute(StepDefinition step, StepHandler handler, StepContext context)
            throws Exception {
        RetryLoop loop = new RetryLoop(step);
        Exception last = null;
        while (loop.hasMoreAttempts()) {
            loop.attemptStarted();
            LOG.debug(
                    "步骤尝试 instId={} stepId={} attempt={}/{}",
                    context.flowInstanceId(),
                    step.stepId(),
                    loop.attempt,
                    loop.maxAttempts);
            try {
                return callWithTimeout(step, handler, context);
            } catch (Exception ex) {
                last = ex;
                if (!loop.shouldRetry(errorClassifier.isRetryable(ex))) {
                    break;
                }
                // 虚拟线程上的退避 sleep 不占平台线程
                Thread.sleep(loop.nextBackoffMs());
            }
        }
        // 重试耗尽或不可重试：最终失败必须留痕（否则「重试了几次、死因是什么」只能靠翻堆栈猜）
        LOG.warn(
                "步骤最终失败 instId={} stepId={} attempts={} errType={} err={}",
                context.flowInstanceId(),
                step.stepId(),
                loop.attempt,
                last == null ? "unknown" : last.getClass().getSimpleName(),
                last == null ? "unknown" : last.getMessage());
        throw last;
    }

    /** 在独立虚拟线程里执行 handler，超时则中断并抛 {@link TimeoutException}。 */
    private StepResult callWithTimeout(
            StepDefinition step, StepHandler handler, StepContext context) throws Exception {
        if (step.timeout().isZero() || step.timeout().isNegative()) {
            return handler.execute(context);
        }
        FutureTask<StepResult> task = new FutureTask<>(() -> handler.execute(context));
        Thread worker = Thread.ofVirtual().name("swy-step-" + step.stepId()).start(task);
        try {
            return task.get(step.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            worker.interrupt();
            LOG.warn(
                    "步骤执行超时 stepId={} timeoutMs={}（已向执行线程发中断）",
                    step.stepId(),
                    step.timeout().toMillis());
            throw new TimeoutException(
                    "步骤 " + step.stepId() + " 超时（>" + step.timeout().toMillis() + "ms）");
        } catch (ExecutionException ex) {
            // 解包业务异常，保留原始堆栈（重试判定需要原始异常类型）
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof Exception e) {
                throw e;
            }
            throw ex;
        }
    }

    /** 重试循环的内部簿记。 */
    private static final class RetryLoop {

        private final StepDefinition step;
        private int attempt;
        private int maxAttempts;

        private RetryLoop(StepDefinition step) {
            this.step = step;
            this.attempt = 0;
            this.maxAttempts = Math.max(1, step.retry().maxAttempts());
        }

        private boolean hasMoreAttempts() {
            return attempt < maxAttempts;
        }

        private void attemptStarted() {
            attempt++;
        }

        private boolean shouldRetry(boolean retryable) {
            return retryable && attempt < maxAttempts;
        }

        private long nextBackoffMs() {
            return step.retry().backoffMs(attempt);
        }
    }
}
