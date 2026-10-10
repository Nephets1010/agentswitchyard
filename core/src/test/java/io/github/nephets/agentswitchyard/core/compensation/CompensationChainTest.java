/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.compensation;

import io.github.nephets.agentswitchyard.core.definition.StepKind;
import io.github.nephets.agentswitchyard.core.instance.StepRecord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 补偿链：反向顺序、失败明细、动作未注册。 */
@DisplayName("CompensationChain（Saga 反向补偿，T02 验收标准 2/3）")
class CompensationChainTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-10-10T08:00:00Z"), ZoneOffset.UTC);

    private static StepRecord succeeded(String instId, String stepId, int seq) {
        return new StepRecord(
                instId,
                stepId,
                seq,
                StepKind.TOOL,
                stepId + "-key",
                io.github.nephets.agentswitchyard.core.instance.StepState.SUCCEEDED,
                "span-" + seq,
                "{}",
                "{\"out\":true}",
                null,
                null,
                1,
                FIXED.instant(),
                FIXED.instant(),
                null,
                null);
    }

    @Test
    @DisplayName("按反向顺序执行补偿（s3→s2→s1），报告可验证")
    void runsInReverseOrder() {
        // 记录调用顺序（单线程测试，CopyOnWriteArrayList 足够）
        List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        CompensationChain chain =
                new CompensationChain(
                        Map.of(
                                "undo1",
                                        (CompensationAction)
                                                executed -> {
                                                    order.add("undo1:" + executed.stepId());
                                                    return CompensationOutcome.ok("ok1");
                                                },
                                "undo2",
                                        (CompensationAction)
                                                executed -> {
                                                    order.add("undo2:" + executed.stepId());
                                                    return CompensationOutcome.ok("ok2");
                                                }),
                        FIXED,
                        step ->
                                switch (step.stepId()) {
                                    case "s1" -> "undo1";
                                    case "s2" -> "undo2";
                                    default -> null;
                                });

        CompensationReport report =
                chain.run(
                        List.of(
                                succeeded("i", "s1", 0),
                                succeeded("i", "s2", 1),
                                succeeded("i", "s3", 2)));

        assertThat(report.allSucceeded()).isTrue();
        assertThat(report.entries()).hasSize(2);
        // ★ 反向顺序：s2 的补偿先于 s1
        assertThat(order).containsExactly("undo2:s2", "undo1:s1");
        assertThat(report.entries().get(0).stepId()).isEqualTo("s2");
        assertThat(report.entries().get(1).stepId()).isEqualTo("s1");
        assertThat(report.entries().get(0).outcome().success()).isTrue();
    }

    @Test
    @DisplayName("单个补偿失败不中断其余补偿，失败明细进报告")
    void failureDoesNotStopOthers() {
        CompensationChain chain =
                new CompensationChain(
                        Map.of(
                                "bad",
                                (CompensationAction)
                                        executed -> {
                                            throw new RuntimeException("补偿动作炸了");
                                        },
                                "good",
                                executed -> CompensationOutcome.ok("撤销成功")),
                        FIXED,
                        step -> step.seq() == 1 ? "bad" : "good");

        CompensationReport report =
                chain.run(List.of(succeeded("i", "s1", 0), succeeded("i", "s2", 1)));

        assertThat(report.allSucceeded()).isFalse();
        assertThat(report.entries()).hasSize(2);
        assertThat(report.entries().get(0).outcome().success()).isFalse();
        assertThat(report.entries().get(0).outcome().error()).contains("炸了");
        assertThat(report.entries().get(1).outcome().success()).isTrue();
    }

    @Test
    @DisplayName("声明了未注册的补偿动作 → 记入失败明细而非抛异常")
    void unregisteredActionReported() {
        CompensationChain chain = new CompensationChain(Map.of(), FIXED, step -> "ghost-action");
        CompensationReport report = chain.run(List.of(succeeded("i", "s1", 0)));
        assertThat(report.allSucceeded()).isFalse();
        assertThat(report.entries().get(0).outcome().error()).contains("ghost-action");
        assertThat(report.entries().get(0).actionRef()).isEqualTo("ghost-action");
    }

    @Test
    @DisplayName("未声明补偿的步骤被跳过")
    void stepsWithoutCompensationSkipped() {
        CompensationChain chain =
                new CompensationChain(
                        Map.of("undo", executed -> CompensationOutcome.ok("ok")),
                        FIXED,
                        step -> null);
        CompensationReport report = chain.run(List.of(succeeded("i", "s1", 0)));
        assertThat(report.entries()).isEmpty();
        assertThat(report.allSucceeded()).isTrue();
    }

    @Test
    @DisplayName("Outcome 工厂：成功清空 error，失败保留")
    void outcomeFactories() {
        assertThat(CompensationOutcome.ok("好").error()).isNull();
        assertThat(CompensationOutcome.fail("坏", "原因").success()).isFalse();
    }
}
