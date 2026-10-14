/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.definition;

import io.github.nephets.agentswitchyard.core.instance.StepContext;
import io.github.nephets.agentswitchyard.core.instance.StepResult;

/**
 * 步骤处理器（业务逻辑入口）。
 *
 * <p>函数式接口：一个 handler 通常就是一个方法引用或 lambda，按 {@code stepId} 绑定到流程。 handler 内部<b>不要</b>自己处理重试 / 幂等 /
 * 审批——那些是本仓的职责，handler 只写业务。
 */
@FunctionalInterface
public interface StepHandler {

    /**
     * 执行业务逻辑。
     *
     * @param context 步骤上下文（输入、traceId、LLM 网关等）
     * @return 步骤结果（输出 + 计量）
     * @throws Exception 业务异常；可重试性由 {@code ErrorClassifier} 判定
     */
    StepResult execute(StepContext context) throws Exception;
}
