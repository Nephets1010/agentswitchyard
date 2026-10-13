/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.idempotency;

import io.github.nephets.agentswitchyard.core.instance.StepRecord;
import io.github.nephets.agentswitchyard.core.store.StepStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 幂等守卫：同一幂等键重复执行时，返回首次结果而不是重复执行。
 *
 * <p>★ 幂等键与步骤记录<b>同表同事务</b>（{@code step_record} 的 {@code (flow_instance_id, step_id, idem_key)}
 * 唯一索引 + {@code ON CONFLICT DO NOTHING}）。 「先查幂等表、再执行、再写步骤表」的经典三段式在并发下有窗口——两个线程都查到「不存在」然后都执行。
 * 这里没有那个窗口：插入本身就是判定。
 *
 * <p>★ 并发重复提交的语义（PRD P0-2「重复执行返回首次结果」）： 输家发现已有 RUNNING 记录时<b>等待</b>赢家出结果，然后复用；赢家失败则后来者获得重试执行权。
 */
public final class IdempotencyGuard {

    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyGuard.class);

    /** 等待并发赢家的最大时长（毫秒）。 */
    static final long WAIT_TIMEOUT_MS = 15_000;

    /** 等待轮询间隔（毫秒；虚拟线程上 sleep 不占平台线程）。 */
    private static final long WAIT_INTERVAL_MS = 20;

    private final StepStore store;

    /** 创建幂等守卫。 */
    public IdempotencyGuard(StepStore store) {
        this.store = store;
    }

    /**
     * 尝试开始执行一个步骤（原子）。
     *
     * <p>返回值语义：
     *
     * <ul>
     *   <li>{@code Optional.empty()}：本次调用赢得执行权（或前者失败后获得重试权），调用方应执行 handler
     *   <li>{@code Optional.of(record)}：首次结果已可复用（{@code SUCCEEDED}）—— 调用方直接使用 {@code
     *       outputJson}，<b>副作用不重复</b>
     * </ul>
     *
     * <p>并发情形：已存在 RUNNING 记录时本调用阻塞等待，直到赢家成功（复用其结果）或失败（本调用获得重试权）。
     *
     * @param flowInstanceId 实例 ID
     * @param candidate 要开始的步骤记录（state=RUNNING，含幂等键）
     * @return 幂等命中时返回已存在的成功记录
     */
    public Optional<StepRecord> begin(String flowInstanceId, StepRecord candidate) {
        StepRecord begun = store.tryBeginStep(candidate);
        if (begun == candidate) {
            return Optional.empty(); // ★ 原子插入成功：执行权归本次调用
        }
        if (begun.state().succeeded()) {
            return Optional.of(begun);
        }
        // 已存在但未成功（RUNNING / FAILED / WAITING_APPROVAL）：等待赢家出结果
        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Optional<StepRecord> current = store.findStep(flowInstanceId, candidate.stepId());
            if (current.isPresent()) {
                StepRecord record = current.get();
                if (record.state().succeeded()) {
                    return Optional.of(record); // 复用首次结果
                }
                if (record.state()
                        == io.github.nephets.agentswitchyard.core.instance.StepState.FAILED) {
                    return Optional.empty(); // 赢家失败，后来者重试执行
                }
            }
            try {
                Thread.sleep(WAIT_INTERVAL_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("幂等等待被中断", ex);
            }
        }
        LOG.warn(
                "幂等等待超时（可能有崩溃残留的 RUNNING 记录），转为重试执行 instId={} stepId={}",
                flowInstanceId,
                candidate.stepId());
        return Optional.empty();
    }

    /**
     * 查询某幂等键的首次结果（不尝试执行）。
     *
     * @param flowInstanceId 实例 ID
     * @param stepId 步骤 ID
     * @param idemKey 幂等键
     * @return 首次执行的记录（若已成功）
     */
    public Optional<StepRecord> firstResult(String flowInstanceId, String stepId, String idemKey) {
        return store.findStep(flowInstanceId, stepId).filter(record -> record.state().succeeded());
    }

    /**
     * 是否为首次执行（幂等键尚无成功记录）。
     *
     * @param flowInstanceId 实例 ID
     * @param stepId 步骤 ID
     * @param idemKey 幂等键
     * @return true 表示从未成功执行过
     */
    public boolean isFirstExecution(String flowInstanceId, String stepId, String idemKey) {
        return firstResult(flowInstanceId, stepId, idemKey).isEmpty();
    }
}
