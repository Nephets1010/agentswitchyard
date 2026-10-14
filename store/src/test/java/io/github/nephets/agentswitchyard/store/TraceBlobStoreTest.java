/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.store;

import io.github.nephets.agentswitchyard.core.replay.ReplayBlob;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** TraceBlobStore（H2）：保存、按摘要命中、列表排序、覆盖写。 */
@DisplayName("TraceBlobStore（回放 blob JDBC 实现）")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TraceBlobStoreTest {

    private TraceBlobStore store;

    @BeforeAll
    void setUp() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:swy-replay;DB_CLOSE_DELAY=-1");
        FlywayMigrator.migrate(ds);
        store = new TraceBlobStore(new JdbcTemplate(ds));
    }

    private static ReplayBlob blob(String spanId, int seq, String digest) {
        return new ReplayBlob(
                "inst-replay",
                spanId,
                seq,
                "mock-gpt",
                digest,
                "{\"text\":\"响应\",\"usage\":{\"inputTokens\":1,\"outputTokens\":2}}",
                Instant.parse("2026-10-10T08:00:00Z"));
    }

    @Test
    @DisplayName("保存 → 按摘要命中；摘要不匹配不命中（确定性来自响应注入，ADR-0004）")
    void findByDigest() {
        store.save(blob("span-a", 0, "sha256:abc"));
        store.save(blob("span-b", 0, "sha256:def"));

        assertThat(store.findByDigest("inst-replay", "sha256:abc")).isPresent();
        assertThat(store.findByDigest("inst-replay", "sha256:abc").orElseThrow().spanId())
                .isEqualTo("span-a");
        assertThat(store.findByDigest("inst-replay", "sha256:miss")).isEmpty();
        assertThat(store.findByDigest("other-instance", "sha256:abc")).isEmpty();
    }

    @Test
    @DisplayName("listBlobs 按 spanId + seq 排序")
    void listOrdered() {
        store.save(blob("span-b", 1, "sha256:b1"));
        store.save(blob("span-b", 0, "sha256:b0"));
        store.save(blob("span-a", 0, "sha256:a0"));
        List<ReplayBlob> blobs = store.listBlobs("inst-replay");
        assertThat(blobs).hasSizeGreaterThanOrEqualTo(3);
        // span-a 在前；span-b 内部 seq 升序
        List<String> keys = blobs.stream().map(ReplayBlob::key).toList();
        assertThat(keys.indexOf("span-a:0")).isLessThan(keys.indexOf("span-b:0"));
        assertThat(keys.indexOf("span-b:0")).isLessThan(keys.indexOf("span-b:1"));
    }

    @Test
    @DisplayName("同主键重复保存覆盖（录制重跑不产生脏数据）")
    void saveOverwrites() {
        store.save(
                new ReplayBlob(
                        "inst-overwrite",
                        "span-x",
                        0,
                        "mock-gpt",
                        "sha256:x",
                        "{\"text\":\"v1\"}",
                        Instant.parse("2026-10-10T08:00:00Z")));
        store.save(
                new ReplayBlob(
                        "inst-overwrite",
                        "span-x",
                        0,
                        "mock-gpt",
                        "sha256:x",
                        "{\"text\":\"v2\"}",
                        Instant.parse("2026-10-10T08:00:01Z")));
        assertThat(store.listBlobs("inst-overwrite")).hasSize(1);
        assertThat(store.listBlobs("inst-overwrite").get(0).responseJson()).contains("v2");
    }
}
