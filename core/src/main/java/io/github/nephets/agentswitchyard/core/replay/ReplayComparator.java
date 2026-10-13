/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.replay;

import io.github.nephets.agentswitchyard.core.instance.StepRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 回放对比器：把原始执行与回放执行的步骤记录逐项对比。
 *
 * <p>对比维度：每步的 {@code outputJson}、{@code usage.totalTokens}、{@code state}。 「同输入 + 同录制响应 = 同输出」的
 * TraceDiff 必须为空（T02 验收标准 5）。
 */
public final class ReplayComparator {

    /** 创建对比器（无状态，可共享）。 */
    public ReplayComparator() {}

    /**
     * 对比原始执行与回放执行。
     *
     * @param originalSteps 原始执行的步骤记录
     * @param replayedSteps 回放执行的步骤记录
     * @return 差异报告
     */
    public TraceDiff compare(List<StepRecord> originalSteps, List<StepRecord> replayedSteps) {
        List<TraceDiff.StepDiff> diffs = new ArrayList<>();
        Map<String, StepRecord> original =
                originalSteps.stream()
                        .collect(Collectors.toMap(StepRecord::stepId, Function.identity()));
        Map<String, StepRecord> replayed =
                replayedSteps.stream()
                        .collect(Collectors.toMap(StepRecord::stepId, Function.identity()));

        for (Map.Entry<String, StepRecord> entry : original.entrySet()) {
            String stepId = entry.getKey();
            StepRecord expected = entry.getValue();
            StepRecord actual = replayed.get(stepId);
            if (actual == null) {
                diffs.add(new TraceDiff.StepDiff(stepId, "presence", "存在", "缺失（回放未执行该步骤）"));
                continue;
            }
            if (!safeEquals(expected.outputJson(), actual.outputJson())) {
                diffs.add(
                        new TraceDiff.StepDiff(
                                stepId,
                                "output",
                                String.valueOf(expected.outputJson()),
                                String.valueOf(actual.outputJson())));
            }
            if (expected.usage() != null
                    && actual.usage() != null
                    && expected.usage().totalTokens() != actual.usage().totalTokens()) {
                diffs.add(
                        new TraceDiff.StepDiff(
                                stepId,
                                "usage.totalTokens",
                                String.valueOf(expected.usage().totalTokens()),
                                String.valueOf(actual.usage().totalTokens())));
            }
            if (expected.state() != actual.state()) {
                diffs.add(
                        new TraceDiff.StepDiff(
                                stepId, "state", expected.state().name(), actual.state().name()));
            }
        }
        for (String stepId : replayed.keySet()) {
            if (!original.containsKey(stepId)) {
                diffs.add(new TraceDiff.StepDiff(stepId, "presence", "缺失（原始执行没有该步骤）", "存在"));
            }
        }
        return new TraceDiff(diffs.isEmpty(), diffs);
    }

    private static boolean safeEquals(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
