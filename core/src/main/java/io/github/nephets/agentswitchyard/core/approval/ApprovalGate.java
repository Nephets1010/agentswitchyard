/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.approval;

import io.github.nephets.agentswitchyard.core.ErrorCode;
import io.github.nephets.agentswitchyard.core.SwitchyardException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;

/**
 * 审批门：挂起 / 恢复 / 超时 / 权限，四件事的唯一入口。
 *
 * <p>★ 挂起是<b>持久化</b>的：进程重启后 {@code listPending} 仍能找到它， 流程实例仍是 {@code WAITING_APPROVAL} ——
 * 这是「审批节点是一等公民」的技术含义（架构 §5.0）。
 */
public final class ApprovalGate {

    private static final Logger LOG = LoggerFactory.getLogger(ApprovalGate.class);

    private final ApprovalStore store;
    private final ApprovalAuthorizer authorizer;
    private final ApprovalChannel channel;
    private final Clock clock;
    private volatile DecidedListener listener;

    /** 审批决策完成后的回调（FlowExecutor 用它续跑 / 补偿）。 */
    @FunctionalInterface
    public interface DecidedListener {

        /**
         * 决策生效后回调（同一决策只会回调一次，由 ApprovalStore 的 CAS 保证）。
         *
         * @param record 决策后的审批记录
         * @param approved 是否通过
         * @param degraded 是否降级通过
         */
        void onDecided(ApprovalRecord record, boolean approved, boolean degraded);
    }

    /**
     * 创建审批门。
     *
     * @param store 审批存储
     * @param authorizer 授权器
     * @param channel 通知渠道
     * @param clock 时钟（可注入，测试禁用 sleep）
     */
    public ApprovalGate(
            ApprovalStore store,
            ApprovalAuthorizer authorizer,
            ApprovalChannel channel,
            Clock clock) {
        this.store = store;
        this.authorizer = authorizer == null ? new RoleApprovalAuthorizer() : authorizer;
        this.channel = channel == null ? new WebhookApprovalChannel() : channel;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** 注册决策回调（FlowExecutor 在装配时调用）。 */
    public void setListener(DecidedListener listener) {
        this.listener = listener;
    }

    /**
     * 挂起：创建审批记录、推送待办。
     *
     * @param request 审批请求
     * @return 审批 ID
     */
    public String suspend(ApprovalRequest request) {
        ApprovalRecord record =
                new ApprovalRecord(
                        java.util.UUID.randomUUID().toString(),
                        request.flowInstanceId(),
                        request.stepId(),
                        request.approverRole(),
                        ApprovalState.PENDING,
                        null,
                        null,
                        request.reason(),
                        false,
                        request.requestedAt() == null ? clock.instant() : request.requestedAt(),
                        null,
                        request.dueAt(),
                        request.timeoutPolicy());
        store.save(record);
        channel.notify(record);
        LOG.info(
                "审批挂起 approvalId={} instId={} stepId={} role={} reason={} timeoutPolicy={}",
                record.approvalId(),
                request.flowInstanceId(),
                request.stepId(),
                request.approverRole(),
                request.reason(),
                request.timeoutPolicy());
        return record.approvalId();
    }

    /**
     * 人工决策（含角色校验、CAS 幂等）。
     *
     * @param approvalId 审批 ID
     * @param decision 决策
     * @return 决策后的记录与「本次调用是否生效」
     * @throws SwitchyardException 审批不存在（SW_BAD_REQUEST）或角色不符（SW_APPROVAL_DENIED）
     */
    public DecisionResult decide(String approvalId, ApprovalDecision decision) {
        ApprovalRecord record =
                store.find(approvalId)
                        .orElseThrow(
                                () ->
                                        new SwitchyardException(
                                                ErrorCode.SW_BAD_REQUEST,
                                                "审批不存在: " + approvalId,
                                                null));
        authorizer.authorize(record, decision);
        ApprovalState target =
                decision.approved() ? ApprovalState.APPROVED : ApprovalState.REJECTED;
        boolean won =
                store.compareAndDecide(
                        approvalId,
                        ApprovalState.PENDING,
                        target,
                        decision.approver(),
                        decision.note(),
                        decision.degraded(),
                        decision.decidedAt() == null ? clock.instant() : decision.decidedAt());
        ApprovalRecord after = store.find(approvalId).orElse(record);
        if (won) {
            LOG.info(
                    "审批决策 approvalId={} approver={} decision={} note={}",
                    approvalId,
                    decision.approver(),
                    target,
                    decision.note());
            DecidedListener current = listener;
            if (current != null) {
                current.onDecided(after, decision.approved(), decision.degraded());
            }
        } else {
            LOG.info("审批决策未生效（已被决策）approvalId={} 当前状态={}", approvalId, after.state());
        }
        return new DecisionResult(after, won);
    }

    /**
     * 对已到期的审批应用超时策略。
     *
     * @param approvalId 审批 ID
     * @return 决策后的记录；未到期 / 已决策 / HOLD_FOREVER 返回原记录
     */
    public ApprovalRecord applyTimeoutPolicy(String approvalId) {
        ApprovalRecord record =
                store.find(approvalId)
                        .orElseThrow(
                                () ->
                                        new SwitchyardException(
                                                ErrorCode.SW_BAD_REQUEST,
                                                "审批不存在: " + approvalId,
                                                null));
        if (record.state() != ApprovalState.PENDING) {
            return record;
        }
        if (record.dueAt() == null
                || record.timeoutPolicy() == ApprovalTimeoutPolicy.HOLD_FOREVER) {
            return record;
        }
        if (clock.instant().isBefore(record.dueAt())) {
            return record; // 未到期
        }
        boolean approved = record.timeoutPolicy().approvedOnTimeout();
        boolean won =
                store.compareAndDecide(
                        approvalId,
                        ApprovalState.PENDING,
                        ApprovalState.TIMEOUT,
                        "system-timeout",
                        approved
                                ? (record.timeoutPolicy() == ApprovalTimeoutPolicy.AUTO_DEGRADE
                                        ? "超时自动降级通过"
                                        : "超时自动通过")
                                : "超时自动拒绝",
                        record.timeoutPolicy() == ApprovalTimeoutPolicy.AUTO_DEGRADE,
                        clock.instant());
        ApprovalRecord after = store.find(approvalId).orElse(record);
        if (won) {
            LOG.info("审批超时 approvalId={} policy={}", approvalId, record.timeoutPolicy());
            DecidedListener current = listener;
            if (current != null) {
                current.onDecided(
                        after,
                        approved,
                        record.timeoutPolicy() == ApprovalTimeoutPolicy.AUTO_DEGRADE);
            }
        } else {
            // 扫描与人工决策并发时 CAS 未赢：不留静默失败（为什么这条到期了却没走超时策略）
            LOG.info("审批超时策略未生效（已被其他决策）approvalId={} 当前状态={}", approvalId, after.state());
        }
        return after;
    }

    /**
     * 指定角色的待审批列表。
     *
     * @param approverRole 角色；null/blank 返回全部
     * @return 待审批记录
     */
    public List<ApprovalRecord> listPending(String approverRole) {
        return store.listPending(approverRole);
    }

    /** 决策结果：记录 + 本次调用是否生效（并发审批只生效一次的可观测证据）。 */
    public record DecisionResult(ApprovalRecord record, boolean applied) {}
}
