/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.compensation;

import io.github.nephets.agentswitchyard.core.instance.StepRecord;

/**
 * 补偿动作（SPI）：如何反向撤销一个已成功步骤的副作用。
 *
 * <p>Saga 语义（架构 §5.1）：不做「回滚到过去」，而是「执行一个新动作把影响抵消」—— 已放的款用冲正抵消，已发的邮件发更正函。补偿动作自身应当<b>幂等</b>（可能被重试）。
 */
@FunctionalInterface
public interface CompensationAction {

    /**
     * 执行补偿。
     *
     * @param executed 已成功步骤的记录（含输出快照，补偿动作从输出里取业务单号等）
     * @return 补偿结果
     */
    CompensationOutcome compensate(StepRecord executed);
}
