/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.executor;

import java.io.Serial;

/**
 * 流程执行异常：业务失败经过重试与补偿后的最终对外异常。
 *
 * <p>★ 补偿失败<b>不掩盖原始异常</b>（架构 §8.4）：原始异常是 cause， 补偿失败全部挂在 suppressed 上。
 */
public class FlowExecutionException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    /** 构造异常。 */
    public FlowExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
