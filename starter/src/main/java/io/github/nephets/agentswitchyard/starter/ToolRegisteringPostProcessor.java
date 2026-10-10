/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.starter;

import io.github.nephets.agentswitchyard.core.tool.SwitchyardTool;
import io.github.nephets.agentswitchyard.core.tool.ToolDescriptor;
import io.github.nephets.agentswitchyard.core.tool.ToolRegistry;

import org.springframework.beans.factory.config.BeanPostProcessor;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Set;

/**
 * 工具注册处理器：扫描 bean 方法上的 {@link SwitchyardTool} 注解并注册进 {@link ToolRegistry}。
 *
 * <p>★ 第一道闸（显式注册 + 注册期校验）在这里生效：未注册的工具调用直接被 {@link ToolSandbox} 拒绝；高危工具没声明 {@code
 * requiresApproval=true} 时， {@link ToolDescriptor} 构造器在注册期就抛异常（fail-fast，不进运行期）。
 */
public class ToolRegisteringPostProcessor implements BeanPostProcessor {

    private final ToolRegistry registry;

    /** 创建处理器。 */
    public ToolRegisteringPostProcessor(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        for (Method method : bean.getClass().getMethods()) {
            SwitchyardTool annotation = method.getAnnotation(SwitchyardTool.class);
            if (annotation != null) {
                registry.register(
                        new ToolDescriptor(
                                annotation.toolName(),
                                annotation.description(),
                                Duration.ofMillis(annotation.timeoutMs()),
                                annotation.maxRetries(),
                                annotation.idempotent(),
                                annotation.requiresApproval(),
                                Set.of(annotation.allowedDomains()),
                                annotation.riskLevel()));
            }
        }
        return bean;
    }
}
