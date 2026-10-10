/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.definition;

import java.time.Duration;

/**
 * 步骤定义：流程的最小可靠单元。
 *
 * <p>★ 可靠性三件套都是<b>声明式</b>的： 幂等键（{@code idempotencyKey}）、补偿（{@code compensation}）、审批（{@code
 * requiresApproval}）。 声明发生在流程定义期而不是执行期——「出了事才知道没声明」是企业流程最贵的错误。
 *
 * @param stepId 步骤 ID，流程内唯一
 * @param kind 步骤类型
 * @param order 执行序号（从 0 开始；同流程内必须连续递增，由 FlowBuilder 保证）
 * @param idempotencyKey 幂等键模板；支持 {@code {inputKey}} 占位符引用流程输入（如 {@code "{ref}:disburse"}）；{@code
 *     null} 时默认为 {@code stepId}
 * @param requiresApproval 执行前是否必须人工审批（挂起等待）
 * @param approvalRole 审批人角色（{@code requiresApproval=true} 时必填；由 ApprovalAuthorizer 校验）
 * @param timeout 单步执行超时（零或负表示不限时）
 * @param retry 重试参数
 * @param compensation 补偿声明
 * @param description 人类可读说明（进轨迹报告）
 */
public record StepDefinition(
        String stepId,
        StepKind kind,
        int order,
        String idempotencyKey,
        boolean requiresApproval,
        String approvalRole,
        Duration timeout,
        RetrySpec retry,
        CompensationSpec compensation,
        String description) {

    /** 紧凑构造器：fail-fast 校验（定义期错误绝不允许拖到执行期）。 */
    public StepDefinition {
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("stepId 不可为空");
        }
        if (kind == null) {
            throw new IllegalArgumentException("步骤 " + stepId + " 缺少 kind");
        }
        if (requiresApproval && (approvalRole == null || approvalRole.isBlank())) {
            throw new IllegalArgumentException(
                    "步骤 " + stepId + " 声明了 requiresApproval 却未指定审批角色 approvalRole");
        }
        if (requiresApproval && kind != StepKind.HUMAN) {
            throw new IllegalArgumentException(
                    "步骤 "
                            + stepId
                            + " 声明了 requiresApproval，但 kind="
                            + kind
                            + "（审批挂载只对 HUMAN 步骤开放）");
        }
        if (kind == StepKind.TOOL && (compensation == null || !compensation.present())) {
            throw new IllegalArgumentException(
                    "步骤 " + stepId + " 是 TOOL 步骤（有外部副作用），必须声明补偿动作 compensation（Saga 语义）");
        }
        if (timeout == null) {
            timeout = Duration.ZERO;
        }
        if (retry == null) {
            retry = RetrySpec.NO_RETRY;
        }
        if (compensation == null) {
            compensation = CompensationSpec.NONE;
        }
    }

    /** 解析幂等键：{@code {key}} 占位符替换为流程输入中的对应值。 */
    public String resolveIdempotencyKey(java.util.Map<String, Object> input) {
        String template =
                (idempotencyKey == null || idempotencyKey.isBlank()) ? stepId : idempotencyKey;
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\\{(\\w+)}").matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Object value = input == null ? null : input.get(matcher.group(1));
            matcher.appendReplacement(
                    out, java.util.regex.Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
