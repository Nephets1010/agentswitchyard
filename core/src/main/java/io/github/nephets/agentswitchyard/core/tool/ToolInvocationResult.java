/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.tool;

/**
 * 工具调用结果。
 *
 * @param success 是否成功
 * @param outputJson 输出 JSON
 * @param error 失败原因（成功时为 null）
 * @param latencyMs 耗时毫秒
 */
public record ToolInvocationResult(
        boolean success, String outputJson, String error, long latencyMs) {

    /** 成功结果。 */
    public static ToolInvocationResult ok(String outputJson, long latencyMs) {
        return new ToolInvocationResult(true, outputJson, null, latencyMs);
    }

    /** 失败结果。 */
    public static ToolInvocationResult fail(String error, long latencyMs) {
        return new ToolInvocationResult(false, null, error, latencyMs);
    }
}
