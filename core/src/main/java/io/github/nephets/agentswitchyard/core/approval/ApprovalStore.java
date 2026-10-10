/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.approval;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 审批存储 SPI（★ SPI 在 core：ApprovalGate 依赖它，JDBC 实现在 store 模块）。
 *
 * <p>实现方必须保证 {@link #compareAndDecide} 是 CAS：同一审批并发提交多个决策， <b>只有第一个生效</b>（「并发审批只生效一次」的硬验收标准）。
 */
public interface ApprovalStore {

    /** 保存新审批（幂等：同 ID 重复保存返回已存在的）。 */
    void save(ApprovalRecord record);

    /** 按 ID 查审批。 */
    Optional<ApprovalRecord> find(String approvalId);

    /**
     * CAS 决策：仅当当前状态等于 {@code expected} 时写入决策。
     *
     * @param approvalId 审批 ID
     * @param expected 期望的当前状态（通常是 PENDING）
     * @param target 目标状态（APPROVED / REJECTED / TIMEOUT）
     * @param approver 审批人
     * @param note 审批意见
     * @param degraded 是否降级
     * @param at 决策时间
     * @return true 表示本调用赢得了决策权；false 表示已被他人决策
     */
    boolean compareAndDecide(
            String approvalId,
            ApprovalState expected,
            ApprovalState target,
            String approver,
            String note,
            boolean degraded,
            Instant at);

    /** 指定角色的待审批列表。 */
    List<ApprovalRecord> listPending(String approverRole);

    /** 全部待审批（超时扫描用）。 */
    List<ApprovalRecord> listAllPending();

    /** 实例的全部审批记录（轨迹导出用）。 */
    List<ApprovalRecord> listByInstance(String flowInstanceId);
}
