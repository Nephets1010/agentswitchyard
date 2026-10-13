/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.replay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * InMemoryReplayStore：无 JDBC 时它是 {@code ReplayStore} 的默认 bean，每条 blob 都带完整 responseJson， 因此<b>必须有界</b>
 * —— 只 put 不 remove 的实现在生产上必然 OOM。
 */
@DisplayName("InMemoryReplayStore（有界淘汰 / P0 加固）")
class InMemoryReplayStoreTest {

    private static final Instant BASE = Instant.parse("2026-10-10T00:00:00Z");

    private static ReplayBlob blob(String instanceId, String spanId, int seq, Instant recordedAt) {
        return new ReplayBlob(
                instanceId,
                spanId,
                seq,
                "mock-chat",
                "sha256:" + spanId + "-" + seq,
                "{\"text\":\"ok\"}",
                recordedAt);
    }

    @Test
    @DisplayName("默认构造（1 万条上限）：存取正常，listBlobs 按 spanId + seq 排序")
    void defaultStoreSavesAndLists() {
        InMemoryReplayStore store = new InMemoryReplayStore();
        store.save(blob("inst-1", "span-b", 1, BASE));
        store.save(blob("inst-1", "span-a", 2, BASE.plusSeconds(1)));
        store.save(blob("inst-1", "span-a", 0, BASE.plusSeconds(2)));
        store.save(blob("inst-2", "span-a", 0, BASE));

        List<ReplayBlob> blobs = store.listBlobs("inst-1");
        assertThat(blobs).extracting(ReplayBlob::spanId, ReplayBlob::seq)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("span-a", 0),
                        org.assertj.core.groups.Tuple.tuple("span-a", 2),
                        org.assertj.core.groups.Tuple.tuple("span-b", 1));
        assertThat(store.listBlobs("inst-2")).hasSize(1);
    }

    @Test
    @DisplayName("超过容量上限：按 recordedAt 升序淘汰最旧的 10%，最新记录保留")
    void evictsOldestWhenOverflowed() {
        InMemoryReplayStore store = new InMemoryReplayStore(10);
        for (int seq = 0; seq < 12; seq++) {
            store.save(blob("inst-1", "span-a", seq, BASE.plusSeconds(seq)));
        }

        List<ReplayBlob> blobs = store.listBlobs("inst-1");
        assertThat(blobs).hasSize(10);
        assertThat(blobs.stream().map(ReplayBlob::seq).toList())
                .isEqualTo(List.of(2, 3, 4, 5, 6, 7, 8, 9, 10, 11));
        // 被淘汰的最旧记录不再按摘要命中
        assertThat(store.findByDigest("inst-1", "sha256:span-a-0")).isEmpty();
        assertThat(store.findByDigest("inst-1", "sha256:span-a-11")).isPresent();
    }

    @Test
    @DisplayName("同 (instanceId, spanId, seq) 重复保存：覆盖且不会撑爆容量")
    void repeatedSaveOverwrites() {
        InMemoryReplayStore store = new InMemoryReplayStore(4);
        for (int i = 0; i < 50; i++) {
            store.save(blob("inst-1", "span-a", 0, BASE.plusSeconds(i)));
        }
        assertThat(store.listBlobs("inst-1")).hasSize(1);
        Optional<ReplayBlob> found = store.findByDigest("inst-1", "sha256:span-a-0");
        assertThat(found).isPresent();
        assertThat(found.orElseThrow().recordedAt()).isEqualTo(BASE.plusSeconds(49));
    }

    @Test
    @DisplayName("非正容量：构造期 fail-fast")
    void nonPositiveCapacityRejected() {
        assertThatThrownBy(() -> new InMemoryReplayStore(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("容量上限");
        assertThatThrownBy(() -> new InMemoryReplayStore(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
