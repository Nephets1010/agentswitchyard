/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.definition;

/**
 * 补偿声明：步骤失败时如何被「反向撤销」。
 *
 * <p>{@code actionRef} 指向一个已注册的 {@link
 * io.github.nephets.agentswitchyard.core.compensation.CompensationAction}（在 Spring 环境里通常是 一个 bean
 * 的名字）。补偿动作是 <b>Saga 语义</b>：不做「回滚到过去」，而是「执行一个新动作把影响抵消」 ——发出去的款用冲正抵消，不是把数据库时光倒流。
 *
 * @param actionRef 补偿动作的注册名；{@code null} 表示该步骤无需补偿（纯读操作）
 */
public record CompensationSpec(String actionRef) {

    /** 无补偿（该步骤无外部副作用）。 */
    public static final CompensationSpec NONE = new CompensationSpec(null);

    /** 声明补偿动作为 {@code actionRef}。 */
    public static CompensationSpec with(String actionRef) {
        return new CompensationSpec(actionRef);
    }

    /** 是否声明了补偿。 */
    public boolean present() {
        return actionRef != null && !actionRef.isBlank();
    }
}
