/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.compensation;

import java.time.Instant;
import java.util.List;

/**
 * 补偿报告（可验证：审计/监管要的是「撤销了什么、结果如何」，不是一句 boolean）。
 *
 * @param entries 每个被补偿步骤的执行明细（反向顺序）
 * @param allSucceeded 是否全部成功
 */
public record CompensationReport(List<Entry> entries, boolean allSucceeded) {

    /** 紧凑构造器：allSucceeded 一律重算，不信任调用方。 */
    public CompensationReport {
        entries = entries == null ? List.of() : List.copyOf(entries);
        allSucceeded = entries.stream().allMatch(e -> e.outcome().success());
    }

    /** 汇总一个补偿批次（entries 应已按反向顺序排好；allSucceeded 自动重算）。 */
    public CompensationReport(List<Entry> entries) {
        this(entries, false);
    }

    /** 汇总一个补偿批次（entries 应已按反向顺序排好）。 */
    public static CompensationReport of(List<Entry> entries) {
        return new CompensationReport(entries);
    }

    /**
     * 单条补偿明细。
     *
     * @param stepId 被补偿的步骤
     * @param actionRef 补偿动作注册名
     * @param outcome 执行结果
     * @param executedAt 执行时间
     */
    public record Entry(
            String stepId, String actionRef, CompensationOutcome outcome, Instant executedAt) {

        /** 紧凑构造器。 */
        public Entry {
            if (outcome == null) {
                outcome = CompensationOutcome.fail("无结果", "outcome 为 null");
            }
        }
    }
}
