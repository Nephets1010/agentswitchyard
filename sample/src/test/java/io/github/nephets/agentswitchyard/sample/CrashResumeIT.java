/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.sample;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★★★ 崩溃恢复端到端（真实 kill -9，验收硬标准 5）：
 *
 * <ol>
 *   <li>子进程启动 sample 应用（真实 JVM、真实 H2 文件库）
 *   <li>启动授信流程，跑到人工审批挂起（WAITING_APPROVAL）
 *   <li>{@code destroyForcibly()} —— 真实 kill -9，不是 mock
 *   <li>新进程重启（starter 自动恢复 + 审批仍持久挂起）
 *   <li>审批通过 → 实例续跑到 SUCCEEDED
 *   <li>断言：前 3 步各执行恰一次（副作用台账每步一行）、放款恰一次、无重复副作用
 * </ol>
 */
@DisplayName("CrashResumeIT（真实 kill -9 崩溃恢复）")
class CrashResumeIT {

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    @Timeout(300)
    @DisplayName("kill -9 后重启：不重复副作用、审批仍挂起、续跑到 SUCCEEDED")
    void crashAndResume() throws Exception {
        Path dbDir = Files.createTempDirectory("swy-crash");
        String dbUrl =
                "jdbc:h2:file:"
                        + dbDir.resolve("swy").toAbsolutePath().toString().replace('\\', '/');
        int port = freePort();

        // ---------- 第一段：跑到审批挂起，然后 kill -9 ----------
        Process first = launch(port, dbUrl, dbDir);
        try {
            awaitHealth(port);
            String instanceId = startFlow(port, "R-CRASH-1", 500_000);
            await(() -> state(port, instanceId).contains("WAITING_APPROVAL"));
        } finally {
            first.destroyForcibly(); // ★ 真实 kill -9（SIGKILL，非优雅停机）
            first.waitFor();
        }
        // OS 释放文件锁需要一瞬；H2 锁文件随进程死亡失效
        Thread.sleep(1_000);

        // ---------- 第二段：新进程重启，审批 → 续跑 ----------
        Process second = launch(port, dbUrl, dbDir);
        try {
            awaitHealth(port);
            // 重启后审批仍持久挂起（WAITING_APPROVAL 是持久状态）
            // 注：临时库只有本用例一笔流程，待办里的唯一 PENDING 即本流程的 manual-review
            String pending = get(port, "/demo/approvals?role=risk-officer");
            assertThat(pending)
                    .as("重启后审批应仍在待办（PENDING 且带 flowInstanceId）")
                    .contains("\"state\":\"PENDING\"")
                    .contains("\"flowInstanceId\":\"");
            String approvalId = extractApprovalId(pending);
            assertThat(approvalId).isNotBlank();

            // 审批通过（重启后仍在待办 = 「挂起是持久化的」证据）
            HttpResponse<String> decide =
                    post(
                            port,
                            "/_switchyard/approval/" + approvalId + "/decide",
                            "{\"approver\":\"zhang-san\",\"approverRole\":\"risk-officer\",\"approved\":true,"
                                    + "\"note\":\"崩溃恢复后审批\",\"degraded\":false}");
            assertThat(decide.statusCode()).isEqualTo(200);
            assertThat(decide.body()).contains("\"applied\":true");

            // 拿实例 ID：待办记录里有 flowInstanceId
            String instanceId = extractInstanceId(pending);
            await(
                    () -> {
                        String state = state(port, instanceId);
                        return state.contains("SUCCEEDED") || state.contains("COMPENSATED");
                    });
            assertThat(state(port, instanceId)).as("审批通过后应续跑到成功").contains("SUCCEEDED");

            // ★ 副作用台账：前 3 步 + 审批步 + 放款 = 5 行，每行唯一（不重复副作用）
            List<Map<String, Object>> ledger = ledgerRows(port, instanceId);
            assertThat(ledger).as("副作用台账应有 1 行放款记录（恰一次）").hasSize(1);
            assertThat(ledger.get(0).get("step_id")).isEqualTo("disburse");
            assertThat(ledger.get(0).get("cancelled")).isEqualTo(Boolean.FALSE);

            // 步骤账本：5 步全部出现且各恰一条（重启后没有重复执行任何一步）
            String instance = get(port, "/demo/flow/" + instanceId);
            for (String stepId :
                    List.of(
                            "ai-screen",
                            "risk-score",
                            "rule-engine",
                            "manual-review",
                            "disburse")) {
                assertThat(countOccurrences(instance, "\"" + stepId + "\""))
                        .as("步骤 %s 在账本中恰出现一次", stepId)
                        .isEqualTo(1);
            }
            assertThat(instance)
                    .as("5 步全部成功")
                    .doesNotContain("\"state\":\"FAILED")
                    .doesNotContain("\"state\":\"WAITING_APPROVAL");
        } finally {
            second.destroyForcibly();
            second.waitFor();
        }
    }

    // ==================== 子进程与 HTTP 辅助 ====================

    private Process launch(int port, String dbUrl, Path workDir) throws IOException {
        String javaBin =
                Path.of(System.getProperty("java.home"), "bin", javaExecutable())
                        .toAbsolutePath()
                        .toString();
        List<String> command =
                List.of(
                        javaBin,
                        "-Dfile.encoding=UTF-8",
                        "-cp",
                        System.getProperty("java.class.path"),
                        "io.github.nephets.agentswitchyard.sample.SampleApplication");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("SERVER_PORT", String.valueOf(port));
        builder.environment().put("SWY_DB_URL", dbUrl);
        builder.environment().put("SWY_CHAT_MODEL", "demo");
        builder.directory(workDir.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(workDir.resolve("app-" + port + ".log").toFile());
        return builder.start();
    }

    private static String javaExecutable() {
        return System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
    }

    private void awaitHealth(int port) throws Exception {
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (get(port, "/demo/health").contains("UP")) {
                    return;
                }
            } catch (Exception ex) {
                // 进程还没起来，继续等
            }
            Thread.sleep(500);
        }
        throw new AssertionError("90s 内子进程未就绪（查看工作目录 app-*.log）");
    }

    private String startFlow(int port, String ref, long amount) throws Exception {
        HttpResponse<String> response =
                post(
                        port,
                        "/demo/flow/start",
                        "{\"ref\":\"" + ref + "\",\"amount\":" + amount + "}");
        assertThat(response.statusCode()).isEqualTo(200);
        String body = response.body();
        int idx = body.indexOf("\"flowInstanceId\":\"");
        assertThat(idx).isGreaterThan(0);
        int start = idx + "\"flowInstanceId\":\"".length();
        return body.substring(start, body.indexOf('"', start));
    }

    /** 实例自身状态（精确解析顶层 state 字段 —— 步骤列表里也有 state，不能字符串模糊匹配）。 */
    @SuppressWarnings("unchecked")
    private String state(int port, String instanceId) {
        try {
            Map<String, Object> body =
                    io.github.nephets.agentswitchyard.core.Json.read(
                            get(port, "/_switchyard/flows/instances/" + instanceId), Map.class);
            return body == null ? "" : String.valueOf(body.get("state"));
        } catch (Exception ex) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> ledgerRows(int port, String instanceId) throws Exception {
        String json = get(port, "/demo/ledger/" + instanceId);
        return io.github.nephets.agentswitchyard.core.Json.read(json, List.class);
    }

    private String get(int port, String path) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private HttpResponse<String> post(int port, String path, String json) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("60s 内未等到期望条件");
            }
            Thread.sleep(100);
        }
    }

    private static int freePort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String extractApprovalId(String json) {
        int idx = json.indexOf("\"approvalId\":\"");
        return idx < 0 ? "" : json.substring(idx + 14, json.indexOf('"', idx + 14));
    }

    private static String extractInstanceId(String json) {
        int idx = json.indexOf("\"flowInstanceId\":\"");
        return idx < 0 ? "" : json.substring(idx + 18, json.indexOf('"', idx + 18));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
