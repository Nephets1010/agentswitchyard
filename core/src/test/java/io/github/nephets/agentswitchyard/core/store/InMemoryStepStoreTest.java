/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.store;

import io.github.nephets.agentswitchyard.core.definition.StepKind;
import io.github.nephets.agentswitchyard.core.instance.FlowInstance;
import io.github.nephets.agentswitchyard.core.instance.FlowState;
import io.github.nephets.agentswitchyard.core.instance.StepRecord;
import io.github.nephets.agentswitchyard.core.instance.StepState;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 容量上限与淘汰（Txx：内存步骤表不可无界增长）。 */
@DisplayName("InMemoryStepStore 容量上限与 LRU 淘汰")
class InMemoryStepStoreTest {

    private static FlowInstance instance(String id) {
        return new FlowInstance(
                id,
                "flow-1",
                1,
                "tenant-1",
                "trace-" + id,
                FlowState.RUNNING,
                Map.of(),
                Instant.parse("2026-10-10T08:00:00Z"),
                Instant.parse("2026-10-10T08:00:00Z"));
    }

    private static StepRecord step(String inst, String stepId) {
        return new StepRecord(
                inst,
                stepId,
                0,
                StepKind.LLM,
                "idem-" + stepId,
                StepState.RUNNING,
                "span-" + stepId,
                "{}",
                null,
                null,
                null,
                1,
                null,
                null,
                null,
                null);
    }

    @Test
    @DisplayName("capacity<=0 抛异常")
    void rejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> new InMemoryStepStore(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InMemoryStepStore(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("插入超过容量会淘汰最旧的流程实例（含其步骤表）")
    void evictsOldestInstanceOnOverflow() {
        int capacity = 3;
        InMemoryStepStore store = new InMemoryStepStore(capacity);

        store.createInstance(instance("inst-1"));
        store.tryBeginStep(step("inst-1", "s1"));
        store.createInstance(instance("inst-2"));
        store.tryBeginStep(step("inst-2", "s2"));
        store.createInstance(instance("inst-3"));
        store.tryBeginStep(step("inst-3", "s3"));

        assertThat(store.findStep("inst-1", "s1")).isPresent();
        assertThat(store.findStep("inst-3", "s3")).isPresent();

        // 第 4 个实例触发淘汰，最旧的 inst-1 及其步骤表应被移除
        store.createInstance(instance("inst-4"));
        store.tryBeginStep(step("inst-4", "s4"));

        assertThat(store.findStep("inst-1", "s1")).isEmpty();
        assertThat(store.findStep("inst-2", "s2")).isPresent();
        assertThat(store.findStep("inst-3", "s3")).isPresent();
        assertThat(store.findStep("inst-4", "s4")).isPresent();
    }

    @Test
    @DisplayName("被访问的实例刷新顺序，访问后不再是最旧")
    void accessRefreshesRecency() {
        int capacity = 3;
        InMemoryStepStore store = new InMemoryStepStore(capacity);

        store.createInstance(instance("inst-1"));
        store.createInstance(instance("inst-2"));
        store.createInstance(instance("inst-3"));

        // 访问 inst-1（loadInstance 触发访问顺序刷新），最旧变为 inst-2
        assertThat(store.loadInstance("inst-1")).isNotNull();

        store.createInstance(instance("inst-4"));

        assertThat(store.loadInstance("inst-1").flowInstanceId()).isEqualTo("inst-1"); // 保留
        assertThat(store.loadInstance("inst-3").flowInstanceId()).isEqualTo("inst-3"); // 保留
        assertThatThrownBy(() -> store.loadInstance("inst-2"))
                .isInstanceOf(IllegalArgumentException.class); // 最旧，淘汰
    }

    @Test
    @DisplayName("容量内状态 CAS 仍然生效")
    void casStillWorksWithinCapacity() {
        InMemoryStepStore store = new InMemoryStepStore(10);
        store.createInstance(instance("inst-x"));

        boolean ok =
                store.compareAndSetInstanceState(
                        "inst-x",
                        FlowState.RUNNING,
                        FlowState.SUCCEEDED,
                        Instant.parse("2026-10-10T09:00:00Z"));
        boolean bad =
                store.compareAndSetInstanceState(
                        "inst-x",
                        FlowState.RUNNING,
                        FlowState.FAILED,
                        Instant.parse("2026-10-10T09:01:00Z"));

        assertThat(ok).isTrue();
        assertThat(bad).isFalse();
        assertThat(store.loadInstance("inst-x").state()).isEqualTo(FlowState.SUCCEEDED);
    }
}
