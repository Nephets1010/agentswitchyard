/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** 淘汰记账器：把有界集合的静默淘汰变成可观测、可断言的降级事件。 */
@DisplayName("EvictionTracker（淘汰记账）")
class EvictionTrackerTest {

    @Test
    @DisplayName("初始零淘汰；每次淘汰累计计数")
    void countsEveryEviction() {
        EvictionTracker tracker = new EvictionTracker("test-subject");
        assertThat(tracker.evictedCount()).isZero();
        tracker.onEvict();
        tracker.onEvict();
        tracker.onEvict();
        assertThat(tracker.evictedCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("并发淘汰不丢计数")
    void concurrentEvictionsDoNotLoseCounts() throws Exception {
        EvictionTracker tracker = new EvictionTracker("test-subject");
        int threads = 8;
        int perThread = 1_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLine = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(
                    pool.submit(
                            () -> {
                                startLine.await();
                                for (int i = 0; i < perThread; i++) {
                                    tracker.onEvict();
                                }
                                return null;
                            }));
        }
        startLine.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();
        assertThat(tracker.evictedCount()).isEqualTo((long) threads * perThread);
    }
}
