/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for full license text.
 */
package io.github.nephets.agentswitchyard.starter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 录制序号跟踪表（P4「存储有界」）：spanId 只增不减，跟踪表必须有界。 */
@DisplayName("SpanSeqTracker 序号分配与有界性")
class SpanSeqTrackerTest {

    @Test
    @DisplayName("同一 span 序号从 0 单调递增，不同 span 独立计数")
    void 同一span序号从0单调递增() {
        SpanSeqTracker tracker = new SpanSeqTracker();
        assertThat(tracker.next("s1")).isZero();
        assertThat(tracker.next("s1")).isEqualTo(1);
        assertThat(tracker.next("s1")).isEqualTo(2);
        assertThat(tracker.next("s2")).isZero();
        assertThat(tracker.next("s1")).isEqualTo(3);
    }

    @Test
    @DisplayName("非正上限被拒")
    void 非正上限被拒() {
        assertThatThrownBy(() -> new SpanSeqTracker(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SpanSeqTracker(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("跟踪表有界：摸过大量 span 后大小不超过上限")
    void 跟踪表有界() {
        SpanSeqTracker tracker = new SpanSeqTracker(4);
        for (int i = 0; i < 200; i++) {
            tracker.next("span-" + i);
        }
        assertThat(tracker.trackedSpans()).isEqualTo(4);
    }

    @Test
    @DisplayName("未超限时序号连续（LRU 不会误伤活跃 span）")
    void 未超限时序号连续() {
        SpanSeqTracker tracker = new SpanSeqTracker(3);
        tracker.next("a");
        tracker.next("b");
        tracker.next("c");
        assertThat(tracker.trackedSpans()).isEqualTo(3);
        assertThat(tracker.next("a")).isEqualTo(1);
        assertThat(tracker.next("b")).isEqualTo(1);
    }
}
