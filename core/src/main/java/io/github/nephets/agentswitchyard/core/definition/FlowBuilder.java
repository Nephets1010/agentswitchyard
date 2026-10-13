/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.definition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 流程定义的 Builder（20 行代码能定义一个带审批的流程，是 README「30 秒上手」的承诺）。
 *
 * <p>用法见 {@code sample} 模块的 {@code CreditApproveFlow}。
 */
public final class FlowBuilder {

    private final String flowId;
    private int version = 1;
    private final List<StepDefinition> steps = new ArrayList<>();

    /** 创建指定 flowId 的 Builder。 */
    public FlowBuilder(String flowId) {
        this.flowId = flowId;
    }

    /** 设置流程版本（默认 1）。 */
    public FlowBuilder version(int version) {
        this.version = version;
        return this;
    }

    /** 追加一个完整定义的步骤。 */
    public FlowBuilder step(StepDefinition step) {
        steps.add(
                new StepDefinition(
                        step.stepId(),
                        step.kind(),
                        steps.size(),
                        step.idempotencyKey(),
                        step.requiresApproval(),
                        step.approvalRole(),
                        step.timeout(),
                        step.retry(),
                        step.compensation(),
                        step.description()));
        return this;
    }

    /** 追加一个 LLM 步骤。 */
    public StepBuilder llmStep(String stepId) {
        return new StepBuilder(this, stepId, StepKind.LLM);
    }

    /** 追加一个工具步骤（必须声明补偿）。 */
    public StepBuilder toolStep(String stepId) {
        return new StepBuilder(this, stepId, StepKind.TOOL);
    }

    /** 追加一个规则步骤。 */
    public StepBuilder ruleStep(String stepId) {
        return new StepBuilder(this, stepId, StepKind.RULE);
    }

    /** 追加一个人工步骤。 */
    public StepBuilder humanStep(String stepId) {
        return new StepBuilder(this, stepId, StepKind.HUMAN);
    }

    /** 构建流程定义。 */
    public FlowDefinition build() {
        return new FlowDefinition(flowId, version, List.copyOf(steps));
    }

    /** 步骤级 Builder（流式声明可靠性三件套）。 */
    public static final class StepBuilder {

        private final FlowBuilder parent;
        private final String stepId;
        private final StepKind kind;
        private String idempotencyKey;
        private boolean requiresApproval;
        private String approvalRole;
        private Duration timeout = Duration.ZERO;
        private RetrySpec retry = RetrySpec.NO_RETRY;
        private CompensationSpec compensation = CompensationSpec.NONE;
        private String description;

        private StepBuilder(FlowBuilder parent, String stepId, StepKind kind) {
            this.parent = parent;
            this.stepId = stepId;
            this.kind = kind;
        }

        /** 幂等键模板（支持 {@code {inputKey}} 占位符）。 */
        public StepBuilder idempotencyKey(String key) {
            this.idempotencyKey = key;
            return this;
        }

        /** 声明执行前需人工审批并指定审批角色（仅 HUMAN 步骤）。 */
        public StepBuilder approvalRequired(String role) {
            this.requiresApproval = true;
            this.approvalRole = role;
            return this;
        }

        /** 单步超时。 */
        public StepBuilder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /** 重试参数。 */
        public StepBuilder retry(RetrySpec retry) {
            this.retry = retry;
            return this;
        }

        /** 补偿动作注册名。 */
        public StepBuilder compensateWith(String actionRef) {
            this.compensation = CompensationSpec.with(actionRef);
            return this;
        }

        /** 人类可读说明。 */
        public StepBuilder description(String description) {
            this.description = description;
            return this;
        }

        /** 追加到流程并继续下一个步骤。 */
        public FlowBuilder done() {
            return parent.step(
                    new StepDefinition(
                            stepId,
                            kind,
                            0,
                            idempotencyKey,
                            requiresApproval,
                            approvalRole,
                            timeout,
                            retry,
                            compensation,
                            description));
        }
    }
}
