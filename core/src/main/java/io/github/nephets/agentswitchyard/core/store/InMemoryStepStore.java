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
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * StepStore 的纯 POJO 内存实现（架构 §8.3：每个 SPI 必须有无 Spring 的实现，保证 core 可单测）。
 *
 * <p>状态 CAS 在实例锁内串行化的「读-改-写」实现，与 JDBC 实现的原子语义对齐。生产环境请用 {@code agentswitchyard-store} 的 JDBC
 * 实现（崩溃恢复需要真持久化）。
 *
 * <p>为避免长时间运行的内存泄漏，流程实例表有容量上限（默认 {@value
 * #DEFAULT_CAPACITY}）。超限时按「访问顺序」淘汰最久未访问（最旧）的流程实例及其步骤表；新创建的实例总是保留。容量可通过构造器覆盖。
 */
public final class InMemoryStepStore implements StepStore {

    /** 默认容量上限：超过则按访问顺序淘汰最久未访问的流程实例。 */
    public static final int DEFAULT_CAPACITY = 1_000_000;

    private final int capacity;
    private final Map<String, FlowInstance> instances;
    private final Map<String, Map<String, StepRecord>> steps = new ConcurrentHashMap<>();

    public InMemoryStepStore() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * 带容量上限的构造器。
     *
     * @param capacity 流程实例容量上限（必须 &gt; 0）
     * @throws IllegalArgumentException capacity 不合法
     */
    public InMemoryStepStore(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity 必须 > 0: " + capacity);
        }
        this.capacity = capacity;
        LinkedHashMap<String, FlowInstance> backing =
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, FlowInstance> eldest) {
                        if (size() > InMemoryStepStore.this.capacity) {
                            // 实例被淘汰时同步清理其步骤表，保持一致性并约束总量
                            steps.remove(eldest.getKey());
                            return true;
                        }
                        return false;
                    }
                };
        this.instances = Collections.synchronizedMap(backing);
    }

    @Override
    public void createInstance(FlowInstance instance) {
        synchronized (instances) {
            instances.putIfAbsent(instance.flowInstanceId(), instance);
        }
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
        synchronized (instances) {
            FlowInstance current = instances.get(flowInstanceId);
            if (current == null) {
                throw new IllegalArgumentException("流程实例不存在: " + flowInstanceId);
            }
            if (current.state() != expected) {
                return false;
            }
            instances.put(flowInstanceId, current.withState(expected.transitionTo(target), at));
            return true;
        }
    }

    @Override
    public void updateInstanceState(String flowInstanceId, FlowState target, Instant at) {
        synchronized (instances) {
            FlowInstance current = instances.get(flowInstanceId);
            if (current == null) {
                throw new IllegalArgumentException("流程实例不存在: " + flowInstanceId);
            }
            instances.put(flowInstanceId, current.withState(target, at));
        }
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
        synchronized (instances) {
            return instances.values().stream()
                    .filter(i -> i.state() == state)
                    .collect(Collectors.toList());
        }
    }

    private void mutate(String flowInstanceId, String stepId, UnaryOperator<StepRecord> fn) {
        Map<String, StepRecord> stepMap = steps.get(flowInstanceId);
        if (stepMap == null) {
            throw new IllegalArgumentException("实例不存在步骤表: " + flowInstanceId);
        }
        stepMap.computeIfPresent(stepId, (k, r) -> fn.apply(r));
    }
}
