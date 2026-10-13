/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.executor;

import io.github.nephets.agentswitchyard.core.definition.StepDefinition;
import io.github.nephets.agentswitchyard.core.instance.StepRecord;

import java.io.Serial;

/** 单步失败异常（executor 内部用，携带失败步骤的记录快照，供失败路径落库）。 */
final class FlowStepException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final StepDefinition step;
    private final StepRecord record;

    FlowStepException(StepDefinition step, StepRecord record, Throwable cause) {
        super("步骤 " + step.stepId() + " 执行失败: " + cause.getMessage(), cause);
        this.step = step;
        this.record = record;
    }

    StepDefinition step() {
        return step;
    }

    StepRecord record() {
        return record;
    }
}
