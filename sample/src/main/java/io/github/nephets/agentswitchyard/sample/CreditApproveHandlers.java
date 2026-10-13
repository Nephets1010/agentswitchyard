/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.sample;

import io.github.nephets.agentswitchyard.core.definition.StepHandler;
import io.github.nephets.agentswitchyard.core.instance.StepResult;
import io.github.nephets.agentswitchyard.core.usage.Usage;
import io.github.nephets.agentswitchyard.sample.ledger.Ledger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 信贷审批流程的业务 handler 集合（bean 名 = stepId，自动配置按名装配进 FlowExecutor）。
 *
 * <p>五个步骤覆盖三类典型：LLM 步骤（经 ctx.chat 走录制/回放）、规则步骤（fail-fast 不可重试）、 工具步骤（真实副作用落 {@link Ledger} 副作用台账 +
 * 回放守卫）。
 */
@Configuration
public class CreditApproveHandlers {

    private final Ledger ledger;

    /** 创建 handler 集合。 */
    public CreditApproveHandlers(Ledger ledger) {
        this.ledger = ledger;
    }

    /** ai-screen：LLM 预审。 */
    @Bean("ai-screen")
    public StepHandler aiScreen() {
        return ctx -> {
            var reply =
                    ctx.chat(
                            "demo-gpt",
                            "信贷预审 ref="
                                    + ctx.input("ref")
                                    + " amount="
                                    + ctx.input("amount")
                                    + "：请给出初筛意见");
            return new StepResult(
                    Map.of("screenText", reply.text(), "screen", "PASS"),
                    reply.usage(),
                    new BigDecimal("0.00030000"));
        };
    }

    /** risk-score：LLM 评分（额度越大分越低 —— 确定性规则驱动，便于回放对齐）。 */
    @Bean("risk-score")
    public StepHandler riskScore() {
        return ctx -> {
            long amount = ctx.input("amount") instanceof Number n ? n.longValue() : 0L;
            int score = amount >= 1_000_000 ? 580 : 720;
            var reply = ctx.chat("demo-gpt", "风险评分 ref=" + ctx.input("ref") + " amount=" + amount);
            return new StepResult(
                    Map.of("score", score, "scoreText", reply.text()),
                    Usage.estimated(
                            io.github.nephets.agentswitchyard.core.usage.UsageSource
                                    .VENDOR_REPORTED,
                            false),
                    new BigDecimal("0.00020000"));
        };
    }

    /** rule-engine：评分 &lt; 600 拒绝（不可重试异常 → 触发反向补偿链）。 */
    @Bean("rule-engine")
    public StepHandler ruleEngine() {
        return ctx -> {
            Map<String, Object> input = ctx.input();
            // 上一步输出经实例输入聚合不可见 —— 简化 demo：直接按 amount 规则判断
            long amount = input.get("amount") instanceof Number n ? n.longValue() : 0L;
            if (Boolean.TRUE.equals(input.get("forceReject")) || amount >= 1_000_000) {
                throw new IllegalArgumentException("规则引擎：评分 580 低于 600，拒绝授信（不可重试）");
            }
            return StepResult.ofRule(Map.of("decision", "PASS", "amount", amount));
        };
    }

    /** manual-review：人工复核步骤（审批通过后 handler 真正执行）。 */
    @Bean("manual-review")
    public StepHandler manualReview() {
        return ctx -> StepResult.ofRule(Map.of("reviewed", true, "note", "人工复核通过"));
    }

    /** disburse：放款（真实副作用：副作用台账唯一行；回放模式跳过副作用、输出与实跑完全一致）。 */
    @Bean("disburse")
    public StepHandler disburse() {
        return ctx -> {
            long amount = ctx.input("amount") instanceof Number n ? n.longValue() : 0L;
            if (ctx.replay()) {
                // ★ 回放不产生真实副作用（ADR-0004）：输出与实跑逐字节一致（确定性回放的前提），仅台账不动
                return StepResult.ofRule(Map.of("disbursed", true, "amount", amount));
            }
            ledger.recordSideEffect(ctx.flowInstanceId(), "disburse", "放款 " + amount + " CNY");
            return StepResult.ofRule(Map.of("disbursed", true, "amount", amount));
        };
    }
}
