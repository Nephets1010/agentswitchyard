/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.compensation;

import io.github.nephets.agentswitchyard.core.instance.StepRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 补偿链：按<b>反向顺序</b>执行已成功步骤的补偿动作（Saga）。
 *
 * <p>★ 两条硬规则（架构 §8.4）：
 *
 * <ol>
 *   <li>补偿失败<b>不掩盖原始异常</b>：FlowExecutor 会把补偿失败挂在 {@code addSuppressed} 上， 原始异常永远是主异常
 *   <li>某个补偿失败<b>不中断</b>其余补偿（尽量多撤销，失败明细进 {@link CompensationReport}）
 * </ol>
 */
public final class CompensationChain {

    private static final Logger LOG = LoggerFactory.getLogger(CompensationChain.class);

    private final Map<String, CompensationAction> actions;
    private final Clock clock;
    private final Function<StepRecord, String> compensationResolver;

    /**
     * 创建补偿链（无声明解析器：所有步骤视为未声明补偿，仅供纯单元测试用）。
     *
     * @param actions 补偿动作注册表（key = CompensationSpec.actionRef）
     * @param clock 时钟（可注入，测试友好）
     */
    public CompensationChain(Map<String, CompensationAction> actions, Clock clock) {
        this(actions, clock, step -> null);
    }

    /**
     * 创建补偿链。
     *
     * @param actions 补偿动作注册表（key = CompensationSpec.actionRef）
     * @param clock 时钟（可注入，测试友好）
     * @param compensationResolver 步骤 → 补偿动作注册名（FlowExecutor 用流程定义提供）
     */
    public CompensationChain(
            Map<String, CompensationAction> actions,
            Clock clock,
            Function<StepRecord, String> compensationResolver) {
        this.actions = actions == null ? Map.of() : Map.copyOf(actions);
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.compensationResolver =
                compensationResolver == null ? step -> null : compensationResolver;
    }

    /**
     * 反向执行补偿：对每个已成功且声明了补偿的步骤，从后往前执行补偿动作。
     *
     * @param executedSteps 已成功的步骤（内部自行按 seq 反向排序）
     * @return 补偿报告（反向顺序，可验证）
     */
    public CompensationReport run(List<StepRecord> executedSteps) {
        List<StepRecord> reversed =
                executedSteps.stream()
                        .filter(r -> r.state().succeeded())
                        .sorted(Comparator.comparingInt(StepRecord::seq).reversed())
                        .toList();

        List<CompensationReport.Entry> entries = new ArrayList<>();
        for (StepRecord step : reversed) {
            String actionRef = compensationResolver.apply(step);
            if (actionRef == null || actionRef.isBlank()) {
                continue; // 未声明补偿（如纯读步骤），跳过
            }
            CompensationAction action = actions.get(actionRef);
            if (action == null) {
                entries.add(
                        new CompensationReport.Entry(
                                step.stepId(),
                                actionRef,
                                CompensationOutcome.fail(
                                        "补偿动作未注册", "找不到 CompensationAction: " + actionRef),
                                clock.instant()));
                continue;
            }
            CompensationOutcome outcome;
            try {
                outcome = action.compensate(step);
            } catch (RuntimeException ex) {
                // 补偿动作抛异常 ≠ 流程异常：捕获为失败明细，继续补偿其余步骤
                LOG.warn(
                        "补偿执行异常 instId={} stepId={} action={} err={}",
                        step.flowInstanceId(),
                        step.stepId(),
                        actionRef,
                        ex.getMessage());
                outcome = CompensationOutcome.fail("补偿动作抛出异常", ex.getMessage());
            }
            entries.add(
                    new CompensationReport.Entry(
                            step.stepId(), actionRef, outcome, clock.instant()));
        }
        return CompensationReport.of(entries);
    }
}
