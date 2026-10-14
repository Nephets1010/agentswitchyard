/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.instance;

import io.github.nephets.agentswitchyard.core.definition.StepKind;
import io.github.nephets.agentswitchyard.core.usage.Usage;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 步骤记录（账本的每一行）。
 *
 * <p>★ 幂等键与步骤记录<b>同表同事务</b>（{@code step_record} 上的 {@code (flow_instance_id, step_id, idem_key)}
 * 唯一索引）：不存在「先查幂等表再执行再写步骤表」 的跨表竞态 —— 那种写法在并发下会重复执行副作用，测试在小并发时能过、生产会资损（架构风险 4）。
 *
 * @param flowInstanceId 流程实例 ID
 * @param stepId 步骤 ID
 * @param seq 步骤序号（从 0 开始；契约 swy.step.seq）
 * @param kind 步骤类型（契约 swy.step.kind）
 * @param idemKey 幂等键
 * @param state 步骤状态
 * @param spanId 本步骤的 W3C spanId（用于回放 blob 的 key 与 ③ 的 span 关联）
 * @param inputJson 输入快照（JSON）
 * @param outputJson 输出快照（JSON）
 * @param usage Token 计量（无 LLM 调用时为零值 Usage，不可为 null）
 * @param costCny 本步成本（CNY，内部 8 位小数；无 LLM 时为 0）
 * @param attempts 已尝试次数
 * @param startedAt 开始时间
 * @param endedAt 结束时间
 * @param errorMessage 失败原因（可读，含错误码）
 * @param approvalId 挂起审批的 ID（{@code state=WAITING_APPROVAL} 时非空）
 */
public record StepRecord(
        String flowInstanceId,
        String stepId,
        int seq,
        StepKind kind,
        String idemKey,
        StepState state,
        String spanId,
        String inputJson,
        String outputJson,
        Usage usage,
        BigDecimal costCny,
        int attempts,
        Instant startedAt,
        Instant endedAt,
        String errorMessage,
        String approvalId) {

    /** 紧凑构造器：null 归一。 */
    public StepRecord {
        if (flowInstanceId == null || flowInstanceId.isBlank()) {
            throw new IllegalArgumentException("flowInstanceId 不可为空");
        }
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("stepId 不可为空");
        }
        if (kind == null) {
            throw new IllegalArgumentException("步骤 " + stepId + " 缺少 kind");
        }
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

    /** 派生：仅改状态。 */
    public StepRecord withState(StepState newState) {
        return new StepRecord(
                flowInstanceId,
                stepId,
                seq,
                kind,
                idemKey,
                newState,
                spanId,
                inputJson,
                outputJson,
                usage,
                costCny,
                attempts,
                startedAt,
                endedAt,
                errorMessage,
                approvalId);
    }

    /** 派生：执行成功（输出 + 计量 + 结束时间）。 */
    public StepRecord succeeded(String output, Usage usage, BigDecimal costCny, Instant endedAt) {
        return new StepRecord(
                flowInstanceId,
                stepId,
                seq,
                kind,
                idemKey,
                StepState.SUCCEEDED,
                spanId,
                inputJson,
                output,
                usage == null ? this.usage : usage,
                costCny == null ? this.costCny : costCny,
                attempts,
                startedAt,
                endedAt,
                null,
                approvalId);
    }

    /** 派生：失败（重试耗尽）。 */
    public StepRecord failed(String message, Instant endedAt) {
        return new StepRecord(
                flowInstanceId,
                stepId,
                seq,
                kind,
                idemKey,
                StepState.FAILED,
                spanId,
                inputJson,
                outputJson,
                usage,
                costCny,
                attempts,
                startedAt,
                endedAt,
                message,
                approvalId);
    }

    /** 派生：挂起等待审批。 */
    public StepRecord waitingApproval(String approvalRecordId) {
        return new StepRecord(
                flowInstanceId,
                stepId,
                seq,
                kind,
                idemKey,
                StepState.WAITING_APPROVAL,
                spanId,
                inputJson,
                outputJson,
                usage,
                costCny,
                attempts,
                startedAt,
                endedAt,
                errorMessage,
                approvalRecordId);
    }

    /** 派生：重试计数 +1。 */
    public StepRecord attemptIncremented() {
        return new StepRecord(
                flowInstanceId,
                stepId,
                seq,
                kind,
                idemKey,
                state,
                spanId,
                inputJson,
                outputJson,
                usage,
                costCny,
                attempts + 1,
                startedAt,
                endedAt,
                errorMessage,
                approvalId);
    }

    /** 步骤耗时毫秒（未结束时按 0）。 */
    public long durationMs() {
        if (startedAt == null || endedAt == null) {
            return 0;
        }
        return Math.max(0, endedAt.toEpochMilli() - startedAt.toEpochMilli());
    }
}
