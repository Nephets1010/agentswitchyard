/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;

/**
 * 审批超时扫描器：扫描到期审批并应用超时策略。
 *
 * <p>★ 时钟可注入（架构 §8.6：测试禁用 Thread.sleep）。调度由外层驱动： starter 里注册一个 {@code @Scheduled} 周期调 {@link
 * #tick()}；单元测试直接手动 tick。
 */
public final class ApprovalTimeoutScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(ApprovalTimeoutScheduler.class);

    private final ApprovalGate gate;
    private final ApprovalStore store;
    private final Clock clock;

    /**
     * 创建扫描器。
     *
     * @param gate 审批门
     * @param store 审批存储（扫描数据源）
     * @param clock 时钟
     */
    public ApprovalTimeoutScheduler(ApprovalGate gate, ApprovalStore store, Clock clock) {
        this.gate = gate;
        this.store = store;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * 扫描一轮：对每个已到期的 PENDING 审批应用超时策略。
     *
     * <p>★ 日志只打汇总、不在循环内逐条打：{@code @Scheduled} 高频空转时每轮一条 debug 也嫌吵， 而「本轮实际触发了几条超时决策」才是运维需要的信息。
     *
     * @return 本轮实际触发超时决策的数量
     */
    public int tick() {
        List<ApprovalRecord> pending = store.listAllPending();
        int triggered = 0;
        for (ApprovalRecord record : pending) {
            if (record.dueAt() == null || clock.instant().isBefore(record.dueAt())) {
                continue;
            }
            ApprovalRecord after = gate.applyTimeoutPolicy(record.approvalId());
            if (after.state().decided()) {
                triggered++;
            }
        }
        if (triggered > 0) {
            LOG.info("审批超时扫描一轮结束 scanned={} triggered={}", pending.size(), triggered);
        } else {
            LOG.debug("审批超时扫描一轮结束 scanned={} triggered=0", pending.size());
        }
        return triggered;
    }
}
