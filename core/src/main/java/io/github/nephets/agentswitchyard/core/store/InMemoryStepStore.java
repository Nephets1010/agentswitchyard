/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.store;

import io.github.nephets.agentswitchyard.core.instance.FlowInstance;
import io.github.nephets.agentswitchyard.core.instance.FlowState;
import io.github.nephets.agentswitchyard.core.instance.StepRecord;
import io.github.nephets.agentswitchyard.core.instance.StepState;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * StepStore 的纯 POJO 内存实现（架构 §8.3：每个 SPI 必须有无 Spring 的实现，保证 core 可单测）。
 *
 * <p>并发语义与 JDBC 实现对齐：{@code tryBeginStep} 用 {@code putIfAbsent} 模拟「同幂等键只允许一行插入成功」；状态 CAS 用 {@link
 * AtomicReference}。 生产环境请用 {@code agentswitchyard-store} 的 JDBC 实现（崩溃恢复需要真持久化）。
 */
public final class InMemoryStepStore implements StepStore {

    private final Map<String, FlowInstance> instances = new ConcurrentHashMap<>();
    private final Map<String, Map<String, StepRecord>> steps = new ConcurrentHashMap<>();

    @Override
    public void createInstance(FlowInstance instance) {
        instances.putIfAbsent(instance.flowInstanceId(), instance);
    }

    @Override
    public FlowInstance loadInstance(String flowInstanceId) {
        FlowInstance instance = instances.get(flowInstanceId);
        if (instance == null) {
            throw new IllegalArgumentException("流程实例不存在: " + flowInstanceId);
        }
        return instance;
    }

    @Override
    public boolean compareAndSetInstanceState(
            String flowInstanceId, FlowState expected, FlowState target, Instant at) {
        AtomicReference<FlowInstance> ref = new AtomicReference<>(loadInstance(flowInstanceId));
        FlowInstance current = ref.get();
        if (current.state() != expected) {
            return false;
        }
        return instances.replace(
                flowInstanceId, current, current.withState(expected.transitionTo(target), at));
    }

    @Override
    public void updateInstanceState(String flowInstanceId, FlowState target, Instant at) {
        FlowInstance current = loadInstance(flowInstanceId);
        instances.put(flowInstanceId, current.withState(target, at));
    }

    @Override
    public StepRecord tryBeginStep(StepRecord candidate) {
        Map<String, StepRecord> stepMap =
                steps.computeIfAbsent(candidate.flowInstanceId(), k -> new ConcurrentHashMap<>());
        StepRecord existing = stepMap.putIfAbsent(candidate.stepId(), candidate);
        return existing == null ? candidate : existing;
    }

    @Override
    public void completeStep(StepRecord record, Instant at) {
        Map<String, StepRecord> stepMap = steps.get(record.flowInstanceId());
        if (stepMap == null) {
            throw new IllegalArgumentException("实例不存在步骤表: " + record.flowInstanceId());
        }
        StepRecord succeeded =
                record.succeeded(record.outputJson(), record.usage(), record.costCny(), at);
        stepMap.put(record.stepId(), succeeded);
    }

    @Override
    public void markWaitingApproval(
            String flowInstanceId, String stepId, String approvalId, Instant at) {
        mutate(flowInstanceId, stepId, r -> r.waitingApproval(approvalId));
    }

    @Override
    public void markFailed(String flowInstanceId, String stepId, String reason, Instant at) {
        mutate(flowInstanceId, stepId, r -> r.failed(reason, at));
    }

    @Override
    public void markCompensated(String flowInstanceId, String stepId, boolean success, Instant at) {
        mutate(
                flowInstanceId,
                stepId,
                r -> r.withState(success ? StepState.COMPENSATED : StepState.COMPENSATE_FAILED));
    }

    @Override
    public void failInterruptedSteps(String flowInstanceId, Instant at) {
        Map<String, StepRecord> stepMap = steps.get(flowInstanceId);
        if (stepMap == null) {
            return;
        }
        stepMap.replaceAll(
                (stepId, record) ->
                        record.state() == StepState.RUNNING
                                ? record.failed("进程中断（崩溃恢复）", at)
                                : record);
    }

    @Override
    public void markStepResumed(String flowInstanceId, String stepId, Instant at) {
        mutate(flowInstanceId, stepId, r -> r.withState(StepState.RUNNING));
    }

    @Override
    public Optional<StepRecord> findStep(String flowInstanceId, String stepId) {
        return Optional.ofNullable(steps.getOrDefault(flowInstanceId, Map.of()).get(stepId));
    }

    @Override
    public List<StepRecord> listSteps(String flowInstanceId) {
        return steps.getOrDefault(flowInstanceId, Map.of()).values().stream()
                .sorted(Comparator.comparingInt(StepRecord::seq))
                .collect(Collectors.toList());
    }

    @Override
    public List<StepRecord> listCompletedSteps(String flowInstanceId) {
        return listSteps(flowInstanceId).stream()
                .filter(r -> r.state().succeeded())
                .collect(Collectors.toList());
    }

    @Override
    public List<FlowInstance> listInstancesInState(FlowState state) {
        return instances.values().stream()
                .filter(i -> i.state() == state)
                .collect(Collectors.toList());
    }

    private void mutate(
            String flowInstanceId, String stepId, java.util.function.UnaryOperator<StepRecord> fn) {
        Map<String, StepRecord> stepMap = steps.get(flowInstanceId);
        if (stepMap == null) {
            throw new IllegalArgumentException("实例不存在步骤表: " + flowInstanceId);
        }
        stepMap.computeIfPresent(stepId, (k, r) -> fn.apply(r));
    }
}
