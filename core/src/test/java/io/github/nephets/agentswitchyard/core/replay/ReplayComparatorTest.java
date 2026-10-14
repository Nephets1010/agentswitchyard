/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.replay;

import io.github.nephets.agentswitchyard.core.definition.StepKind;
import io.github.nephets.agentswitchyard.core.instance.StepRecord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 回放对比：同输入同快照 → TraceDiff 为空（T02 验收标准 5）。 */
@DisplayName("ReplayComparator（差异为空 = 确定性成立）")
class ReplayComparatorTest {

    private static StepRecord record(String stepId, int seq, String output, long totalTokens) {
        return new StepRecord(
                "inst",
                stepId,
                seq,
                StepKind.LLM,
                stepId + "-key",
                io.github.nephets.agentswitchyard.core.instance.StepState.SUCCEEDED,
                "span-" + seq,
                "{}",
                output,
                io.github.nephets.agentswitchyard.core.usage.Usage.vendorReported(totalTokens, 1),
                null,
                1,
                Instant.EPOCH,
                Instant.EPOCH,
                null,
                null);
    }

    private final ReplayComparator comparator = new ReplayComparator();

    @Test
    @DisplayName("同输出 + 同计量 + 同状态 → 差异为空")
    void identicalTracesGiveEmptyDiff() {
        TraceDiff diff =
                comparator.compare(
                        List.of(record("s1", 0, "{\"r\":1}", 10), record("s2", 1, "{\"r\":2}", 20)),
                        List.of(
                                record("s1", 0, "{\"r\":1}", 10),
                                record("s2", 1, "{\"r\":2}", 20)));

        assertThat(diff.identical()).isTrue();
        assertThat(diff.diffs()).isEmpty();
        assertThat(TraceDiff.empty().identical()).isTrue();
    }

    @Test
    @DisplayName("输出不一致 → 可读差异（含字段名与两侧值）")
    void outputMismatchReported() {
        TraceDiff diff =
                comparator.compare(
                        List.of(record("s1", 0, "{\"r\":\"通过\"}", 10)),
                        List.of(record("s1", 0, "{\"r\":\"拒绝\"}", 10)));

        assertThat(diff.identical()).isFalse();
        assertThat(diff.diffs()).hasSize(1);
        assertThat(diff.diffs().get(0).stepId()).isEqualTo("s1");
        assertThat(diff.diffs().get(0).field()).isEqualTo("output");
        assertThat(diff.diffs().get(0).expected()).contains("通过");
        assertThat(diff.diffs().get(0).actual()).contains("拒绝");
    }

    @Test
    @DisplayName("计量不一致 / 缺步 / 多步都被捕获")
    void usageAndPresenceDiffs() {
        TraceDiff diff =
                comparator.compare(
                        List.of(record("s1", 0, "{}", 10), record("s2", 1, "{}", 5)),
                        List.of(record("s1", 0, "{}", 99)));

        assertThat(diff.identical()).isFalse();
        assertThat(diff.diffs())
                .extracting(TraceDiff.StepDiff::field)
                .containsExactlyInAnyOrder("usage.totalTokens", "presence");
    }
}
