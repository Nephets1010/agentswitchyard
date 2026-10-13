/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.definition;

/**
 * 步骤类型（契约 SPN-001：{@code swy.step.kind} 的取值，跨三仓字面一致）。
 *
 * <p>★ 本仓不实现图引擎（ADR-0002）：流程是「有序步骤」，没有分支与循环—— 那是 LangGraph4j 的地盘，我们在它的下游补可靠性。
 */
public enum StepKind {

    /** 模型调用步骤（LLM）。 */
    LLM,

    /** 工具调用步骤（外部副作用，需幂等键 + 补偿声明）。 */
    TOOL,

    /** 纯规则/计算步骤（无外部副作用）。 */
    RULE,

    /** 人工步骤（通常配合 {@code requiresApproval=true} 挂起等待审批）。 */
    HUMAN,

    /** 补偿专用步骤（由补偿链反向触发，不出现在主流程顺序执行中）。 */
    COMPENSATE
}
