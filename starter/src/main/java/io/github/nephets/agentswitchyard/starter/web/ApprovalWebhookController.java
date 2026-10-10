/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.starter.web;

import io.github.nephets.agentswitchyard.core.approval.ApprovalDecision;
import io.github.nephets.agentswitchyard.core.approval.ApprovalGate;
import io.github.nephets.agentswitchyard.core.approval.ApprovalRecord;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;

/**
 * 审批 Webhook 入口（HITL 的 HTTP 面）。
 *
 * <p>★ 并发决策只生效一次：{@link ApprovalGate#decide} 内部是 CAS（JdbcApprovalStore 的 条件
 * UPDATE），两个审批人同时点「同意」只有一个生效，响应里 {@code applied} 如实告知。
 */
@RestController
@Validated
@RequestMapping("/_switchyard/approval")
public class ApprovalWebhookController {

    private final ApprovalGate approvalGate;

    /** 创建控制器。 */
    public ApprovalWebhookController(ApprovalGate approvalGate) {
        this.approvalGate = approvalGate;
    }

    /** 决策请求体。 */
    public record DecideRequest(
            @NotBlank String approver,
            @NotBlank String approverRole,
            boolean approved,
            String note,
            boolean degraded) {}

    /** 提交决策（幂等：同审批重复提交 applied=false）。 */
    @PostMapping("/{approvalId}/decide")
    public ResponseEntity<Map<String, Object>> decide(
            @PathVariable String approvalId, @RequestBody DecideRequest request) {
        ApprovalGate.DecisionResult result =
                approvalGate.decide(
                        approvalId,
                        new ApprovalDecision(
                                request.approver(),
                                request.approverRole(),
                                request.approved(),
                                request.note() == null ? "" : request.note(),
                                request.degraded(),
                                null));
        Map<String, Object> body = new HashMap<>();
        body.put("approvalId", approvalId);
        body.put("applied", result.applied());
        body.put("state", result.record().state().name());
        body.put("approver", result.record().approver());
        body.put("note", result.record().note());
        return ResponseEntity.ok(body);
    }

    /** 待审批列表（按角色过滤；不传角色返回全部）。 */
    @GetMapping("/pending")
    public List<Map<String, Object>> pending(@RequestParam(required = false) String role) {
        return approvalGate.listPending(role).stream()
                .map(ApprovalWebhookController::toView)
                .toList();
    }

    static Map<String, Object> toView(ApprovalRecord record) {
        Map<String, Object> view = new HashMap<>();
        view.put("approvalId", record.approvalId());
        view.put("flowInstanceId", record.flowInstanceId());
        view.put("stepId", record.stepId());
        view.put("approverRole", record.approverRole());
        view.put("state", record.state().name());
        view.put("reason", record.reason());
        view.put(
                "requestedAt",
                record.requestedAt() == null ? null : record.requestedAt().toString());
        view.put("timeoutPolicy", record.timeoutPolicy().name());
        return view;
    }
}
