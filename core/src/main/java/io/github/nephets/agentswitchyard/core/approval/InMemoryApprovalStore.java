/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * ApprovalStore 的纯 POJO 内存实现（架构 §8.3；测试与零依赖 demo 用）。
 *
 * <p>CAS 用「读-改-写」在实例锁内串行化保证：同一 approvalId 的并发决策只有一个生效。
 *
 * <p>为避免长时间运行的内存泄漏，记录表有容量上限（默认 {@value
 * #DEFAULT_CAPACITY}）。超限时按「访问顺序」淘汰最久未访问（最旧）的记录；新插入的总是保留。容量可通过构造器覆盖。
 */
public final class InMemoryApprovalStore implements ApprovalStore {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryApprovalStore.class);

    /** 默认容量上限：超过则按访问顺序淘汰最久未访问的记录。 */
    public static final int DEFAULT_CAPACITY = 1_000_000;

    private final int capacity;
    private final Map<String, ApprovalRecord> records;
    private final AtomicInteger evicted = new AtomicInteger();

    public InMemoryApprovalStore() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * 带容量上限的构造器。
     *
     * @param capacity 容量上限（必须 &gt; 0）
     * @throws IllegalArgumentException capacity 不合法
     */
    public InMemoryApprovalStore(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity 必须 > 0: " + capacity);
        }
        this.capacity = capacity;
        LinkedHashMap<String, ApprovalRecord> backing =
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, ApprovalRecord> eldest) {
                        if (size() <= InMemoryApprovalStore.this.capacity) {
                            return false;
                        }
                        // 淘汰必须可观测：审批单无声消失会让「等审批的流程永远挂起」，
                        // 而现象只是「找不到审批单」，极难排查。首次立刻 WARN，之后每 1000 条汇总一次。
                        int n = evicted.incrementAndGet();
                        if (n == 1 || n % 1000 == 0) {
                            LOG.warn(
                                    "审批记录表达到容量 {}，按访问顺序淘汰最久未访问的审批单 {}（累计淘汰 {}）；"
                                            + "被淘汰的审批单将查不到，等待它的流程会一直挂起",
                                    InMemoryApprovalStore.this.capacity,
                                    eldest.getKey(),
                                    n);
                        }
                        return true;
                    }
                };
        this.records = Collections.synchronizedMap(backing);
    }

    /**
     * @return 累计被容量淘汰的审批单数量（0 = 从未触发淘汰），作为容量降级的可观测标记
     */
    public int evictedCount() {
        return evicted.get();
    }

    @Override
    public void save(ApprovalRecord record) {
        synchronized (records) {
            records.putIfAbsent(record.approvalId(), record);
        }
    }

    @Override
    public Optional<ApprovalRecord> find(String approvalId) {
        synchronized (records) {
            return Optional.ofNullable(records.get(approvalId));
        }
    }

    @Override
    public boolean compareAndDecide(
            String approvalId,
            ApprovalState expected,
            ApprovalState target,
            String approver,
            String note,
            boolean degraded,
            Instant at) {
        synchronized (records) {
            ApprovalRecord current = records.get(approvalId);
            if (current == null || current.state() != expected) {
                return false; // CAS 失败：不存在或已被他人决策
            }
            records.put(
                    approvalId,
                    new ApprovalRecord(
                            current.approvalId(),
                            current.flowInstanceId(),
                            current.stepId(),
                            current.approverRole(),
                            target,
                            approver,
                            note,
                            current.reason(),
                            degraded,
                            current.requestedAt(),
                            at,
                            current.dueAt(),
                            current.timeoutPolicy()));
            return true;
        }
    }

    @Override
    public List<ApprovalRecord> listPending(String approverRole) {
        synchronized (records) {
            return records.values().stream()
                    .filter(r -> r.state() == ApprovalState.PENDING)
                    .filter(
                            r ->
                                    approverRole == null
                                            || approverRole.isBlank()
                                            || r.approverRole().equals(approverRole))
                    .sorted(
                            Comparator.comparing(
                                    ApprovalRecord::requestedAt,
                                    Comparator.nullsLast(Comparator.naturalOrder())))
                    .collect(Collectors.toList());
        }
    }

    @Override
    public List<ApprovalRecord> listAllPending() {
        return listPending(null);
    }

    @Override
    public List<ApprovalRecord> listByInstance(String flowInstanceId) {
        synchronized (records) {
            return records.values().stream()
                    .filter(r -> r.flowInstanceId().equals(flowInstanceId))
                    .sorted(
                            Comparator.comparing(
                                    ApprovalRecord::requestedAt,
                                    Comparator.nullsLast(Comparator.naturalOrder())))
                    .collect(Collectors.toList());
        }
    }
}
