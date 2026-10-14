/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 有界集合淘汰的可观测记账器：把「静默丢数据」变成「可见的降级事件」。
 *
 * <p>★ 为什么需要：内存集合加了容量上限后，淘汰从「异常」变成「静默行为」—— 不记账就意味着运维看不到「回放素材正在流失」或「补偿报告正在被挤掉」。
 * 但淘汰可能高频发生（批量回填、压测），逐条 warn 会刷满日志。
 *
 * <p>故采用聚合节流：首次淘汰立即 warn（保证「发生过」一定可见），之后每 {@value #REPORT_INTERVAL} 条汇总一条 —— 既不留静默失败，也不在热路径上逐条打日志。
 *
 * <p>线程安全：计数为 {@link AtomicLong}，并发调用不丢数；「一次计数对应一次真实淘汰」 仍要求调用方在集合的淘汰点（持锁或集合自身同步）调用。
 */
public final class EvictionTracker {

    /** 汇总节流间隔：首次后每这么多条淘汰打一条汇总 warn。 */
    private static final long REPORT_INTERVAL = 10_000L;

    private final Logger log;
    private final String subject;

    private final AtomicLong count = new AtomicLong();

    /**
     * 创建记账器。
     *
     * @param subject 被记账的对象描述（进日志，如 "compensationReports"）
     */
    public EvictionTracker(String subject) {
        this.subject = subject;
        this.log = LoggerFactory.getLogger(EvictionTracker.class);
    }

    /** 记录一次淘汰（首次与每 {@value #REPORT_INTERVAL} 的倍数条打 warn，其余静默计数）。 */
    public void onEvict() {
        long n = count.incrementAndGet();
        if (n == 1L || n % REPORT_INTERVAL == 0L) {
            log.warn("有界集合淘汰发生 subject={} evictedTotal={}", subject, n);
        }
    }

    /** 累计淘汰次数（0 = 从未淘汰；回归测试断言「淘汰可见」用）。 */
    public long evictedCount() {
        return count.get();
    }
}
