/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.approval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 容量上限与淘汰（Txx：内存审批表不可无界增长）。 */
@DisplayName("InMemoryApprovalStore 容量上限与 LRU 淘汰")
class InMemoryApprovalStoreTest {

    private static ApprovalRecord rec(String id) {
        return new ApprovalRecord(
                id,
                "inst-1",
                "step-1",
                "reviewer",
                ApprovalState.PENDING,
                null,
                null,
                "reason",
                false,
                Instant.parse("2026-10-10T08:00:00Z"),
                null,
                null,
                ApprovalTimeoutPolicy.HOLD_FOREVER);
    }

    @Test
    @DisplayName("默认构造器在常规数量下不淘汰")
    void defaultConstructorKeepsEntries() {
        InMemoryApprovalStore store = new InMemoryApprovalStore();
        store.save(rec("a"));
        store.save(rec("b"));
        assertThat(store.find("a")).isPresent();
        assertThat(store.find("b")).isPresent();
    }

    @Test
    @DisplayName("capacity<=0 抛异常")
    void rejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> new InMemoryApprovalStore(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InMemoryApprovalStore(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("插入超过容量会淘汰最旧的（访问顺序）记录")
    void evictsOldestOnOverflow() {
        int capacity = 3;
        InMemoryApprovalStore store = new InMemoryApprovalStore(capacity);

        store.save(rec("a"));
        store.save(rec("b"));
        store.save(rec("c"));

        // 第 4 条触发淘汰，最旧的 "a" 应被移除（插入后不访问，a 即最旧）
        store.save(rec("d"));

        assertThat(store.find("a")).isEmpty();
        assertThat(store.find("b")).isPresent();
        assertThat(store.find("c")).isPresent();
        assertThat(store.find("d")).isPresent();
    }

    @Test
    @DisplayName("被访问的记录刷新顺序，访问后不再是最旧")
    void accessRefreshesRecency() {
        int capacity = 3;
        InMemoryApprovalStore store = new InMemoryApprovalStore(capacity);

        store.save(rec("a"));
        store.save(rec("b"));
        store.save(rec("c"));

        // 访问 "a" 使其成为「最近使用」，最旧变为 "b"
        assertThat(store.find("a")).isPresent();

        store.save(rec("d"));

        assertThat(store.find("a")).isPresent(); // 被访问过，保留
        assertThat(store.find("b")).isEmpty(); // 最旧，淘汰
        assertThat(store.find("c")).isPresent();
        assertThat(store.find("d")).isPresent();
    }

    @Test
    @DisplayName("容量内的并发决策 CAS 仍然只生效一次")
    void casStillWorksWithinCapacity() {
        InMemoryApprovalStore store = new InMemoryApprovalStore(10);
        store.save(rec("x"));

        boolean first =
                store.compareAndDecide(
                        "x",
                        ApprovalState.PENDING,
                        ApprovalState.APPROVED,
                        "u1",
                        "ok",
                        false,
                        Instant.parse("2026-10-10T09:00:00Z"));
        boolean second =
                store.compareAndDecide(
                        "x",
                        ApprovalState.PENDING,
                        ApprovalState.REJECTED,
                        "u2",
                        "no",
                        false,
                        Instant.parse("2026-10-10T09:01:00Z"));

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(store.find("x"))
                .get()
                .extracting(ApprovalRecord::state)
                .isEqualTo(ApprovalState.APPROVED);
    }

    @Test
    @DisplayName("淘汰不再静默：evictedCount 可观测，且按访问顺序淘汰最久未访问的")
    void 淘汰可观测且按访问顺序() {
        InMemoryApprovalStore store = new InMemoryApprovalStore(2);
        assertThat(store.evictedCount()).isZero();

        store.save(rec("a"));
        store.save(rec("b"));
        store.find("a"); // 刷新 a，使 b 成为最久未访问
        store.save(rec("c")); // 触发淘汰：应淘汰 b 而不是 a

        assertThat(store.evictedCount()).isEqualTo(1);
        assertThat(store.find("b")).isEmpty();
        assertThat(store.find("a")).isPresent();
        assertThat(store.find("c")).isPresent();
    }
}
