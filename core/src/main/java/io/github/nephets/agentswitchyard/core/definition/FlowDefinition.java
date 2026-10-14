/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.definition;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 流程定义：有序步骤 + 声明式可靠性三件套。
 *
 * <p>★ 定位边界（ADR-0002）：这不是图引擎。没有分支、没有循环、没有 StateGraph—— 「图怎么画」是 LangGraph4j 的事，我们只保证「图跑起来之后不出事」。
 *
 * @param flowId 流程 ID（如 {@code credit-approve}）
 * @param version 流程版本（从 1 开始）
 * @param steps 有序步骤列表
 */
public record FlowDefinition(String flowId, int version, List<StepDefinition> steps) {

    /** 紧凑构造器：校验步骤序号连续、stepId 唯一、不可为空流程。 */
    public FlowDefinition {
        if (flowId == null || flowId.isBlank()) {
            throw new IllegalArgumentException("flowId 不可为空");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version 从 1 开始，实际为：" + version);
        }
        steps = steps == null ? List.of() : List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("流程 " + flowId + " 至少要有一个步骤");
        }
        for (int i = 0; i < steps.size(); i++) {
            StepDefinition step = steps.get(i);
            if (step.order() != i) {
                throw new IllegalArgumentException(
                        "流程 " + flowId + " 的步骤序号必须连续递增：期望 order=" + i + "，实际 " + step.order());
            }
        }
        Map<String, Long> duplicated =
                steps.stream()
                        .collect(
                                Collectors.groupingBy(
                                        StepDefinition::stepId, Collectors.counting()));
        duplicated.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .findFirst()
                .ifPresent(
                        e -> {
                            throw new IllegalArgumentException(
                                    "流程 " + flowId + " 的 stepId 重复：" + e.getKey());
                        });
    }

    /** 按 stepId 查步骤；不存在抛 {@link IllegalArgumentException}。 */
    public StepDefinition step(String stepId) {
        return steps.stream()
                .filter(s -> s.stepId().equals(stepId))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("流程 " + flowId + " 不存在步骤 " + stepId));
    }

    /** 构建 DSL 入口。 */
    public static FlowBuilder builder(String flowId) {
        return new FlowBuilder(flowId);
    }
}
