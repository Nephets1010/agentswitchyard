/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.store;

import io.github.nephets.agentswitchyard.core.instance.FlowInstance;
import io.github.nephets.agentswitchyard.core.instance.FlowState;
import io.github.nephets.agentswitchyard.core.instance.StepRecord;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 步骤存储 SPI（★ SPI 定义在 core：FlowExecutor 依赖它，JDBC 实现在 store 模块 —— 依赖必须单向）。
 *
 * <p>实现方必须保证：
 *
 * <ul>
 *   <li>{@link #tryBeginStep} 是<b>原子</b>的：同幂等键并发插入只允许一行成功 （JDBC 实现用 {@code ON CONFLICT DO NOTHING /
 *       INSERT IGNORE / MERGE} 四方言）
 *   <li>幂等键与步骤记录<b>同表同事务</b>
 *   <li>{@link #compareAndSetInstanceState} 是<b>CAS</b>：并发状态转移只允许一个成功
 * </ul>
 */
public interface StepStore {

    /** 新建流程实例（幂等：同 ID 重复创建返回已存在的）。 */
    void createInstance(FlowInstance instance);

    /** 加载流程实例；不存在抛 {@link IllegalArgumentException}。 */
    FlowInstance loadInstance(String flowInstanceId);

    /** 实例状态 CAS 转移；期望状态不符返回 false。 */
    boolean compareAndSetInstanceState(
            String flowInstanceId, FlowState expected, FlowState target, Instant at);

    /** 实例状态强制写入（仅初始化与恢复路径使用；正常执行走 CAS）。 */
    void updateInstanceState(String flowInstanceId, FlowState target, Instant at);

    /**
     * 原子开始步骤（幂等判定点）。
     *
     * <p>同 {@code (flow_instance_id, step_id, idem_key)} 已有记录时<b>不插入</b>， 返回已存在的记录；否则插入 {@code
     * state=RUNNING} 的新记录并返回。 ★ 实现契约：插入成功时必须返回<b>传入的 candidate 对象本身</b>（调用方以引用相等判定「赢得执行权」）。
     */
    StepRecord tryBeginStep(StepRecord candidate);

    /** 完成步骤（输出 + 计量 + 结束时间，状态置 SUCCEEDED）。 */
    void completeStep(StepRecord record, Instant at);

    /** 标记步骤挂起等待审批。 */
    void markWaitingApproval(String flowInstanceId, String stepId, String approvalId, Instant at);

    /** 标记步骤失败。 */
    void markFailed(String flowInstanceId, String stepId, String reason, Instant at);

    /** 标记步骤补偿结果。 */
    void markCompensated(String flowInstanceId, String stepId, boolean success, Instant at);

    /**
     * 崩溃恢复前置：把实例残留的 {@code RUNNING} 步骤记录置为 {@code FAILED} （进程已死，这些记录永远不会被完成；置 FAILED 后幂等守卫允许重新执行）。
     */
    void failInterruptedSteps(String flowInstanceId, Instant at);

    /** 审批通过后续跑：把 {@code WAITING_APPROVAL} 的步骤记录置回 {@code RUNNING}。 */
    void markStepResumed(String flowInstanceId, String stepId, Instant at);

    /** 按主键查步骤。 */
    Optional<StepRecord> findStep(String flowInstanceId, String stepId);

    /** 实例的全部步骤（按 seq 升序）。 */
    List<StepRecord> listSteps(String flowInstanceId);

    /** 已成功完成的步骤（按 seq 升序；补偿链的输入）。 */
    List<StepRecord> listCompletedSteps(String flowInstanceId);

    /** 全部处于指定状态的实例（崩溃恢复用）。 */
    List<FlowInstance> listInstancesInState(FlowState state);
}
