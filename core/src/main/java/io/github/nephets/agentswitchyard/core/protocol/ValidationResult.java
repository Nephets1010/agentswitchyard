/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.protocol;

import java.util.List;

/**
 * 校验结果（非法消息被拒绝时必须给出<b>可读</b>错误：含字段名与原因 —— T04 验收标准 1）。
 *
 * @param valid 是否合法
 * @param errors 错误明细（每条含字段名与原因）
 */
public record ValidationResult(boolean valid, List<String> errors) {

    /** 紧凑构造器：valid 一律重算。 */
    public ValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
        valid = errors == null || errors.isEmpty();
    }

    /** 校验通过。 */
    public static ValidationResult ok() {
        return new ValidationResult(true, List.of());
    }

    /** 校验失败。 */
    public static ValidationResult fail(List<String> errors) {
        return new ValidationResult(false, errors);
    }
}
