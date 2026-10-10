/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.instance;

import io.github.nephets.agentswitchyard.core.usage.Usage;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 步骤执行结果（handler 的返回值）。
 *
 * @param output 输出（JSON 序列化后进 step_record.output_json 与轨迹导出）
 * @param usage Token 计量（无 LLM 调用时填 {@link Usage#estimated}）
 * @param costCny 本步成本（CNY；无 LLM 时为 0）
 */
public record StepResult(Map<String, Object> output, Usage usage, BigDecimal costCny) {

    /** 紧凑构造器：null 归一。 */
    public StepResult {
        output = output == null ? Map.of() : Map.copyOf(output);
        if (usage == null) {
            usage =
                    Usage.estimated(
                            io.github.nephets.agentswitchyard.core.usage.UsageSource.FALLBACK,
                            false);
        }
        if (costCny == null) {
            costCny = BigDecimal.ZERO;
        }
    }

    /** 纯规则步骤的便捷工厂（无计量）。 */
    public static StepResult ofRule(Map<String, Object> output) {
        return new StepResult(output, null, BigDecimal.ZERO);
    }
}
