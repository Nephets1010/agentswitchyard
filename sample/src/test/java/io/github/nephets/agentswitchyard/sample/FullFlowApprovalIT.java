/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.sample;

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

/** 全流程审批闭环（HTTP 层）：启动 → 挂起 → 审批 → SUCCEEDED → 副作用台账有且仅有一行放款。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("FullFlowApprovalIT（审批闭环）")
class FullFlowApprovalIT {

    @Autowired private FlowExecutor flowExecutor;

    @Autowired private TestRestTemplate rest;

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("20s 内未等到期望条件");
            }
            Thread.sleep(20);
        }
    }

    @Test
    @DisplayName("500000 额度申请：审批通过 → SUCCEEDED，放款恰一次")
    void approvalHappyPath() throws Exception {
        String instanceId =
                flowExecutor.start(
                        "credit-approve",
                        Map.of("ref", "R-APP-1", "amount", 500_000),
                        "tenant-demo");
        await(() -> flowExecutor.instance(instanceId).state() == FlowState.WAITING_APPROVAL);

        String approvalId =
                extractApprovalId(
                        rest.getForEntity(
                                        "/_switchyard/approval/pending?role=risk-officer",
                                        String.class)
                                .getBody(),
                        instanceId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> decide =
                rest.postForEntity(
                        "/_switchyard/approval/" + approvalId + "/decide",
                        new HttpEntity<>(
                                "{\"approver\":\"zhang-san\",\"approverRole\":\"risk-officer\",\"approved\":true,"
                                        + "\"note\":\"同意授信\",\"degraded\":false}",
                                headers),
                        String.class);
        assertThat(decide.getBody()).contains("\"applied\":true");

        await(() -> flowExecutor.instance(instanceId).state() == FlowState.SUCCEEDED);
        assertThat(flowExecutor.steps(instanceId)).hasSize(5);

        // 副作用台账：放款有且仅有一行
        String ledger = rest.getForEntity("/demo/ledger/" + instanceId, String.class).getBody();
        assertThat(ledger).contains("disburse");
        assertThat(countOccurrences(ledger, "disburse")).isEqualTo(1);
    }

    static String extractApprovalId(String json, String instanceId) {
        int idx = json == null ? -1 : json.indexOf(instanceId);
        assertThat(idx).as("待办中应含实例 " + instanceId + "，实际返回: " + json).isGreaterThan(0);
        String head = json.substring(0, idx);
        int keyIdx = head.lastIndexOf("\"approvalId\":\"");
        int start = keyIdx + "\"approvalId\":\"".length();
        return json.substring(start, json.indexOf('"', start));
    }

    static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
