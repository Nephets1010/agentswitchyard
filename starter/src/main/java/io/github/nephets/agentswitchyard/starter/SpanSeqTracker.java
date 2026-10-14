/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for full license text.
 */
package io.github.nephets.agentswitchyard.starter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按 spanId 分配单调递增序号的跟踪表 —— 确定性回放的素材键是 {@code (instanceId, spanId, seq)}， 序号在同一个 span 内必须严格递增且从 0
 * 开始，否则回放会错位。
 *
 * <p>★ 为什么必须有界：spanId 由调用方生成、每次对话一个，进程不重启就会一直累积 —— 无界 map 是长跑进程里最典型的静默内存泄漏（P4「存储有界」）。
 *
 * <p>★ 为什么用 LRU 淘汰而不是「超限就拒绝」：序号断了会让同一 span 出现重复键，比丢几个旧 span 更糟； 淘汰只影响「早已离开的 span
 * 又回来继续录制」这种罕见情形，并且每次都打 WARN 留痕，不会静默。
 */
final class SpanSeqTracker {

    private static final Logger LOG = LoggerFactory.getLogger(SpanSeqTracker.class);

    /** 默认跟踪的 span 数上限。 */
    static final int DEFAULT_MAX_TRACKED_SPANS = 8_192;

    private final int maxTrackedSpans;
    private final Map<String, Integer> seqBySpan;

    SpanSeqTracker() {
        this(DEFAULT_MAX_TRACKED_SPANS);
    }

    /**
     * @param maxTrackedSpans 跟踪上限（必须 &gt; 0）
     * @throws IllegalArgumentException 上限非正
     */
    SpanSeqTracker(int maxTrackedSpans) {
        if (maxTrackedSpans < 1) {
            throw new IllegalArgumentException("maxTrackedSpans 必须 > 0: " + maxTrackedSpans);
        }
        this.maxTrackedSpans = maxTrackedSpans;
        this.seqBySpan =
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                        if (size() <= maxTrackedSpans) {
                            return false;
                        }
                        LOG.warn(
                                "录制序号跟踪表达到上限 {}，淘汰最久未录制的 spanId={}"
                                        + "（该 span 若继续录制，序号将重新从 0 开始，可能产生重复键）",
                                maxTrackedSpans,
                                eldest.getKey());
                        return true;
                    }
                };
    }

    /**
     * @return 该 span 的下一个序号（同一 span 从 0 开始单调递增）
     */
    synchronized int next(String spanId) {
        return seqBySpan.merge(spanId, 1, Integer::sum) - 1;
    }

    /**
     * @return 当前跟踪的 span 数（测试与诊断用）
     */
    synchronized int trackedSpans() {
        return seqBySpan.size();
    }

    /**
     * @return 跟踪上限
     */
    int maxTrackedSpans() {
        return maxTrackedSpans;
    }
}
