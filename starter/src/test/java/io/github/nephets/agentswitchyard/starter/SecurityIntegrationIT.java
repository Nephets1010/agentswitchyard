/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.starter;

import io.github.nephets.agentswitchyard.core.executor.FlowExecutor;
import io.github.nephets.agentswitchyard.core.instance.FlowState;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** ★ 安全集成：角色花名册生效 —— 不在花名册的审批人被 403 拒绝，审批保持 PENDING。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("SecurityIntegration（花名册授权）")
class SecurityIntegrationIT {

    @Autowired private StarterTestApplication.FlowExecutorExpose expose;

    @Autowired private TestRestTemplate rest;

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("15s 内未等到期望条件");
            }
            Thread.sleep(20);
        }
    }

    @Test
    @DisplayName("花名册外的人决策 → 403，审批仍 PENDING；花名册内的人 → 生效")
    void rosterEnforced() throws Exception {
        FlowExecutor executor = expose.executor();
        String instanceId = executor.start("it-flow", Map.of("ref", "IT-SEC-1"), "tenant-it");
        await(() -> executor.instance(instanceId).state() == FlowState.WAITING_APPROVAL);

        ResponseEntity<String> pending =
                rest.getForEntity("/_switchyard/approval/pending?role=risk-officer", String.class);
        String approvalId = extractApprovalId(pending.getBody(), instanceId);

        // 花名册外：wang-wu 不在 risk-officer 花名册
        ResponseEntity<String> denied = decide(approvalId, "wang-wu", true);
        assertThat(denied.getStatusCode().value()).as("花名册外应 403").isEqualTo(403);
        assertThat(executor.instance(instanceId).state()).isEqualTo(FlowState.WAITING_APPROVAL);

        // 角色不符：zhang-san 声称 manager 角色
        ResponseEntity<String> wrongRole = decideAsRole(approvalId, "zhang-san", "manager", true);
        assertThat(wrongRole.getStatusCode().value()).as("角色不符应 403").isEqualTo(403);
        assertThat(executor.instance(instanceId).state()).isEqualTo(FlowState.WAITING_APPROVAL);

        // 花名册内：zhang-san 以正确角色 → 生效
        ResponseEntity<String> granted = decide(approvalId, "zhang-san", true);
        assertThat(granted.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(granted.getBody()).contains("\"applied\":true");
        await(() -> executor.instance(instanceId).state().terminal());
        assertThat(executor.instance(instanceId).state()).isEqualTo(FlowState.SUCCEEDED);
    }

    private ResponseEntity<String> decide(String approvalId, String approver, boolean approved) {
        return decideAsRole(approvalId, approver, "risk-officer", approved);
    }

    private ResponseEntity<String> decideAsRole(
            String approvalId, String approver, String role, boolean approved) {
        String body =
                "{\"approver\":\""
                        + approver
                        + "\",\"approverRole\":\""
                        + role
                        + "\",\"approved\":"
                        + approved
                        + ",\"note\":\"安全 IT\",\"degraded\":false}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(
                "/_switchyard/approval/" + approvalId + "/decide",
                new HttpEntity<>(body, headers),
                String.class);
    }

    private static String extractApprovalId(String json, String instanceId) {
        int idx = json.indexOf(instanceId);
        assertThat(idx).as("待办中应含实例 " + instanceId).isGreaterThan(0);
        String head = json.substring(0, idx);
        int keyIdx = head.lastIndexOf("\"approvalId\":\"");
        int start = keyIdx + "\"approvalId\":\"".length();
        return json.substring(start, json.indexOf('"', start));
    }
}
