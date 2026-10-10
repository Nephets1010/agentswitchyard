/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.starter;

import io.github.nephets.agentswitchyard.core.definition.StepHandler;
import io.github.nephets.agentswitchyard.core.executor.FlowExecutor;
import io.github.nephets.agentswitchyard.core.instance.StepResult;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

import java.util.Map;

/**
 * IT 测试应用：注册 it-flow 三个步骤的 handler（bean 名 = stepId）。
 *
 * <p>{@code web} 与 {@code boot} 包排除出组件扫描：控制器与 Web 自动装配统一由 {@code
 * SwitchyardWebAutoConfiguration}（imports 文件）注册，避免双注册冲突 —— 与外部用户应用的注册路径保持一致。
 */
@SpringBootApplication
@ComponentScan(
        basePackages = "io.github.nephets.agentswitchyard.starter",
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern =
                                "io\\.github\\.nephets\\.agentswitchyard\\.starter\\.(web|boot)\\..*"))
public class StarterTestApplication {

    /** prepare 步骤 handler（bean 名 = stepId）。 */
    @Bean("prepare")
    public StepHandler prepareHandler() {
        return ctx -> StepResult.ofRule(Map.of("prepared", true));
    }

    /** manual-review 步骤 handler。 */
    @Bean("manual-review")
    public StepHandler manualReviewHandler() {
        return ctx -> StepResult.ofRule(Map.of("reviewed", true));
    }

    /** finalize 步骤 handler。 */
    @Bean("finalize")
    public StepHandler finalizeHandler() {
        return ctx -> StepResult.ofRule(Map.of("finalized", true));
    }

    /** 供断言用的引擎引用。 */
    @Bean
    public FlowExecutorExpose flowExecutorExpose(FlowExecutor executor) {
        return new FlowExecutorExpose(executor);
    }

    /** 引擎暴露（测试可见）。 */
    public record FlowExecutorExpose(FlowExecutor executor) {}
}
