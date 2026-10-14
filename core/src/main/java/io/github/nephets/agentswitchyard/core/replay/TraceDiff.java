/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.replay;

import java.util.List;

/**
 * 回放差异报告（监管要的是「哪里不一样」，不是一个布尔值）。
 *
 * @param identical 是否完全一致（diffs 为空的等价断言，重算不信任调用方）
 * @param diffs 逐项差异
 */
public record TraceDiff(boolean identical, List<StepDiff> diffs) {

    /** 紧凑构造器：identical 一律重算。 */
    public TraceDiff {
        diffs = diffs == null ? List.of() : List.copyOf(diffs);
        identical = diffs == null || diffs.isEmpty();
    }

    /** 空差异（回放 100% 一致）。 */
    public static TraceDiff empty() {
        return new TraceDiff(true, List.of());
    }

    /**
     * 单条差异。
     *
     * @param stepId 差异所在步骤
     * @param field 差异字段（output / usage.totalTokens / state）
     * @param expected 原始执行值
     * @param actual 回放值
     */
    public record StepDiff(String stepId, String field, String expected, String actual) {}
}
