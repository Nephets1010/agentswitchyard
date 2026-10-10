/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.instance;

import java.time.Instant;
import java.util.Map;

/**
 * 流程实例（一次流程执行的账本头）。
 *
 * @param flowInstanceId 实例 ID（UUID）
 * @param flowId 流程定义 ID
 * @param flowVersion 流程定义版本
 * @param tenantId 租户（可 null；进契约维度 swy.tenant.id）
 * @param traceId W3C traceId（与仓库①③同源贯通）
 * @param state 当前状态
 * @param input 流程输入（JSON 持久化）
 * @param startedAt 开始时间
 * @param updatedAt 最后状态变更时间
 */
public record FlowInstance(
        String flowInstanceId,
        String flowId,
        int flowVersion,
        String tenantId,
        String traceId,
        FlowState state,
        Map<String, Object> input,
        Instant startedAt,
        Instant updatedAt) {

    /** 紧凑构造器：fail-fast 校验。 */
    public FlowInstance {
        if (flowInstanceId == null || flowInstanceId.isBlank()) {
            throw new IllegalArgumentException("flowInstanceId 不可为空");
        }
        if (flowId == null || flowId.isBlank()) {
            throw new IllegalArgumentException("flowId 不可为空");
        }
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("流程实例 " + flowInstanceId + " 缺少 traceId（三仓同源主键）");
        }
        if (state == null) {
            state = FlowState.PENDING;
        }
        input = input == null ? Map.of() : Map.copyOf(input);
    }

    /**
     * 状态转移（校验状态机），返回新实例。
     *
     * @param target 目标状态
     * @param at 变更时间
     * @return 新实例
     * @throws IllegalStateException 非法转移
     */
    public FlowInstance withState(FlowState target, Instant at) {
        state.transitionTo(target);
        return new FlowInstance(
                flowInstanceId,
                flowId,
                flowVersion,
                tenantId,
                traceId,
                target,
                input,
                startedAt,
                at);
    }
}
