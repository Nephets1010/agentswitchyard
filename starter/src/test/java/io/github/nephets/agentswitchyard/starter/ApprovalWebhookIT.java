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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** ★ 审批 Webhook 端到端：启动 → 挂起 → 并发提交两个决策 → 只有第一个生效 → 实例续跑到 SUCCEEDED。 （「并发审批只生效一次」硬验收的 HTTP 层证据。） */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("ApprovalWebhook（HTTP 端到端）")
class ApprovalWebhookIT {

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
    @DisplayName("并发决策 2 次：applied 仅一次，实例最终 SUCCEEDED")
    void concurrentDecideAppliedOnce() throws Exception {
        FlowExecutor executor = expose.executor();
        String instanceId = executor.start("it-flow", Map.of("ref", "IT-WH-1"), "tenant-it");

        await(() -> executor.instance(instanceId).state() == FlowState.WAITING_APPROVAL);

        // 从待办列表拿 approvalId
        ResponseEntity<String> pending =
                rest.getForEntity("/_switchyard/approval/pending?role=risk-officer", String.class);
        assertThat(pending.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pending.getBody()).contains(instanceId);
        String approvalId = extractApprovalId(pending.getBody(), instanceId);

        // 并发提交两个决策（一个同意一个拒绝）
        AtomicInteger appliedCount = new AtomicInteger();
        Thread approver =
                new Thread(() -> appliedCount.addAndGet(decide(approvalId, "zhang-san", true)));
        Thread rejecter =
                new Thread(() -> appliedCount.addAndGet(decide(approvalId, "li-si", false)));
        approver.start();
        rejecter.start();
        approver.join(10_000);
        rejecter.join(10_000);

        assertThat(appliedCount.get()).as("并发决策只有 1 个生效").isEqualTo(1);

        await(() -> executor.instance(instanceId).state().terminal());
        assertThat(executor.instance(instanceId).state())
                .as("生效的决策驱动实例终态")
                .isIn(FlowState.SUCCEEDED, FlowState.COMPENSATED);
        // 二次提交同审批：applied=false（幂等）
        assertThat(decide(approvalId, "zhang-san", true)).isZero();
    }

    private int decide(String approvalId, String approver, boolean approved) {
        String body =
                "{\"approver\":\""
                        + approver
                        + "\",\"approverRole\":\"risk-officer\",\"approved\":"
                        + approved
                        + ",\"note\":\"IT 决策\",\"degraded\":false}";
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<String> response =
                rest.postForEntity(
                        "/_switchyard/approval/" + approvalId + "/decide",
                        new org.springframework.http.HttpEntity<>(body, headers),
                        String.class);
        return response.getStatusCode().is2xxSuccessful()
                        && response.getBody() != null
                        && response.getBody().contains("\"applied\":true")
                ? 1
                : 0;
    }

    private static String extractApprovalId(String json, String instanceId) {
        // 待办 JSON 数组里含该实例的 approvalId；轻量解析（避免引入额外依赖）
        int idx = json.indexOf(instanceId);
        assertThat(idx).as("待办中应含实例 " + instanceId).isGreaterThan(0);
        String head = json.substring(0, idx);
        int keyIdx = head.lastIndexOf("\"approvalId\":\"");
        int start = keyIdx + "\"approvalId\":\"".length();
        return json.substring(start, json.indexOf('"', start));
    }
}
