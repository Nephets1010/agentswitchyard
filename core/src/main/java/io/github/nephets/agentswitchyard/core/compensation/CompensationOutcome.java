/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.compensation;

/**
 * 补偿执行结果。
 *
 * @param success 是否成功
 * @param message 可读说明（进 CompensationReport 与轨迹）
 * @param error 失败原因（成功时为 null）
 */
public record CompensationOutcome(boolean success, String message, String error) {

    /** 紧凑构造器：成功时清空 error。 */
    public CompensationOutcome {
        if (success) {
            error = null;
        }
    }

    /** 成功结果。 */
    public static CompensationOutcome ok(String message) {
        return new CompensationOutcome(true, message, null);
    }

    /** 失败结果。 */
    public static CompensationOutcome fail(String message, String error) {
        return new CompensationOutcome(false, message, error);
    }
}
