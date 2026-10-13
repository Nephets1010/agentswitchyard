/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.starter.web;

import io.github.nephets.agentswitchyard.core.ErrorCode;
import io.github.nephets.agentswitchyard.core.SwitchyardException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/** 全局契约化错误映射（ERR-001）：SwitchyardException → {errorCode, message} + 对应 HTTP 状态。 */
@RestControllerAdvice
public class ApiExceptionAdvice {

    /** SwitchyardException → 结构化错误响应。 */
    @ExceptionHandler(SwitchyardException.class)
    public ResponseEntity<Map<String, Object>> handleSwitchyard(SwitchyardException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("errorCode", ex.errorCode().name());
        body.put("message", ex.getMessage());
        return ResponseEntity.status(statusOf(ex.errorCode())).body(body);
    }

    static int statusOf(ErrorCode errorCode) {
        return switch (errorCode) {
            case SW_BAD_REQUEST -> 400;
            case SW_APPROVAL_DENIED -> 403;
            case SW_MODEL_UNAVAILABLE -> 503;
            default -> 500;
        };
    }
}
