/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.replay;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ReplayStore 的纯 POJO 内存实现（架构 §8.3）。
 *
 * <p>★ 有界：无 JDBC 时本类是 {@code ReplayStore} 的<b>默认</b> bean，而每条 blob 都带完整 {@code responseJson} ——
 * 无界增长在生产上必然 OOM。故设容量上限（默认 10_000 条），超限即按 {@code recordedAt} 升序淘汰最旧的 10%（相比 LRU
 * 更贴合回放语义：越老的录制越不可能被回放命中）。
 */
public final class InMemoryReplayStore implements ReplayStore {

    /** 默认容量上限（条）。 */
    private static final int DEFAULT_MAX_BLOBS = 10_000;

    /** 超限时淘汰的最旧记录占比（百分之）。 */
    private static final int EVICTION_PERCENT = 10;

    /** recordedAt 升序比较器（null 视为最旧，保证淘汰不会因缺时间戳而 NPE）。 */
    private static final Comparator<Instant> OLDEST_FIRST =
            Comparator.nullsFirst(Comparator.naturalOrder());

    private final Map<String, ReplayBlob> blobs = new ConcurrentHashMap<>();

    /** blob 数量上限；{@code save} 后超限即触发淘汰。 */
    private final int maxBlobs;

    /** 默认容量（10_000 条）的内存回放存储。 */
    public InMemoryReplayStore() {
        this(DEFAULT_MAX_BLOBS);
    }

    /**
     * 指定容量的内存回放存储。
     *
     * @param maxBlobs blob 数量上限（必须为正）
     * @throws IllegalArgumentException maxBlobs 非正
     */
    public InMemoryReplayStore(int maxBlobs) {
        if (maxBlobs <= 0) {
            throw new IllegalArgumentException("回放存储容量上限必须为正数: " + maxBlobs);
        }
        this.maxBlobs = maxBlobs;
    }

    @Override
    public void save(ReplayBlob blob) {
        blobs.put(blob.instanceId() + ":" + blob.key(), blob);
        evictIfOverflowed();
    }

    @Override
    public Optional<ReplayBlob> findByDigest(String instanceId, String requestDigest) {
        return blobs.values().stream()
                .filter(b -> b.instanceId().equals(instanceId))
                .filter(b -> b.requestDigest().equals(requestDigest))
                .findFirst();
    }

    @Override
    public List<ReplayBlob> listBlobs(String instanceId) {
        return blobs.values().stream()
                .filter(b -> b.instanceId().equals(instanceId))
                .sorted(Comparator.comparing(ReplayBlob::spanId).thenComparingInt(ReplayBlob::seq))
                .collect(Collectors.toList());
    }

    /**
     * 超限淘汰：删掉最旧的 10%（至少 1 条）。
     *
     * <p>并发下不做加锁：{@code remove} 幂等，最坏情况是瞬时略超上限（内存有界这一硬目标不受影响）， 换取 {@code save} 路径不引入全局锁。
     */
    private void evictIfOverflowed() {
        int size = blobs.size();
        if (size <= maxBlobs) {
            return;
        }
        int evictCount = Math.max(1, size * EVICTION_PERCENT / 100);
        blobs.values().stream()
                .sorted(Comparator.comparing(ReplayBlob::recordedAt, OLDEST_FIRST))
                .limit(evictCount)
                .forEach(blob -> blobs.remove(blob.instanceId() + ":" + blob.key()));
    }
}
