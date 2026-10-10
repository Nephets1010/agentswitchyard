/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.approval;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ApprovalStore 的纯 POJO 内存实现（架构 §8.3；测试与零依赖 demo 用）。
 *
 * <p>CAS 用 {@link ConcurrentHashMap#compute} 实现：同一 approvalId 的并发决策只有一个生效。
 */
public final class InMemoryApprovalStore implements ApprovalStore {

    private final Map<String, ApprovalRecord> records = new ConcurrentHashMap<>();

    @Override
    public void save(ApprovalRecord record) {
        records.putIfAbsent(record.approvalId(), record);
    }

    @Override
    public Optional<ApprovalRecord> find(String approvalId) {
        return Optional.ofNullable(records.get(approvalId));
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
        boolean[] won = {false};
        records.compute(
                approvalId,
                (id, current) -> {
                    if (current == null || current.state() != expected) {
                        return current; // CAS 失败：已被他人决策
                    }
                    won[0] = true;
                    return new ApprovalRecord(
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
                            current.timeoutPolicy());
                });
        return won[0];
    }

    @Override
    public List<ApprovalRecord> listPending(String approverRole) {
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

    @Override
    public List<ApprovalRecord> listAllPending() {
        return listPending(null);
    }

    @Override
    public List<ApprovalRecord> listByInstance(String flowInstanceId) {
        return records.values().stream()
                .filter(r -> r.flowInstanceId().equals(flowInstanceId))
                .sorted(
                        Comparator.comparing(
                                ApprovalRecord::requestedAt,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());
    }
}
