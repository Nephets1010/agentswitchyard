/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** WebhookApprovalChannel：未配置回调地址时必须走「仅留痕」降级，不允许抛异常打断挂起。 */
@DisplayName("WebhookApprovalChannel（降级安全 + 加固）")
class WebhookApprovalChannelTest {

    private static ApprovalRecord record() {
        return record("人工复核");
    }

    private static ApprovalRecord record(String reason) {
        return new ApprovalRecord(
                "appr-ch-1",
                "inst-ch-1",
                "manual-review",
                "risk-officer",
                ApprovalState.PENDING,
                null,
                null,
                reason,
                false,
                Instant.parse("2026-10-10T08:00:00Z"),
                null,
                null,
                ApprovalTimeoutPolicy.HOLD_FOREVER);
    }

    @Test
    @DisplayName("无 webhook 配置：notify 仅留痕（log-only 降级，不抛异常）")
    void notifyWithoutUrlIsSafe() {
        WebhookApprovalChannel channel = new WebhookApprovalChannel();
        assertThatCode(() -> channel.notify(record())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("非法 webhook 地址：降级为留痕并记录，不抛异常打断挂起路径")
    void notifyWithInvalidUrlDoesNotBreakSuspend() {
        WebhookApprovalChannel channel =
                new WebhookApprovalChannel("http://127.0.0.1:1/no-such-hook");
        assertThatCode(() -> channel.notify(record())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("非 http/https 协议（file:// 等）：构造期 fail-fast，杜绝被照发的 SSRF 面")
    void nonHttpSchemeRejectedAtConstruction() {
        assertThatThrownBy(() -> new WebhookApprovalChannel("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http/https");
        assertThatThrownBy(() -> new WebhookApprovalChannel("ftp://internal/hook"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("blank 地址：按「未配置」归一为仅日志，不炸启动")
    void blankUrlTreatedAsUnconfigured() {
        assertThatCode(() -> new WebhookApprovalChannel("   ").notify(record()))
                .doesNotThrowAnyException();
        assertThatCode(() -> new WebhookApprovalChannel("").notify(record()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("大写 scheme（HTTP://）视为合法：协议判定大小写不敏感")
    void upperCaseSchemeAccepted() {
        assertThatCode(() -> new WebhookApprovalChannel("HTTP://127.0.0.1:1/hook"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName(
            "JSON 转义：reason 含反斜杠 / 引号 / 制表符 / 控制字符时，接收端拿到的仍是合法 JSON 且值可原样还原")
    void escapedBodyIsValidJson() throws Exception {
        String nasty = "路径 C:\\tmp\\a \"引用\" \t 制表 \u0001 控制 \r\n 换行";
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> approvalHeader = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/hook",
                exchange -> {
                    try {
                        body.set(
                                new String(
                                        exchange.getRequestBody().readAllBytes(),
                                        StandardCharsets.UTF_8));
                        approvalHeader.set(
                                exchange.getRequestHeaders().getFirst("X-Switchyard-Approval-Id"));
                        byte[] ok = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, ok.length);
                        exchange.getResponseBody().write(ok);
                    } catch (IOException io) {
                        exchange.sendResponseHeaders(500, -1);
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        try {
            WebhookApprovalChannel channel =
                    new WebhookApprovalChannel(
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
            channel.notify(record(nasty));

            JsonNode parsed = new ObjectMapper().readTree(body.get());
            assertThat(parsed.get("reason").asText()).isEqualTo(nasty);
            assertThat(parsed.get("approvalId").asText()).isEqualTo("appr-ch-1");
            assertThat(approvalHeader.get()).isEqualTo("appr-ch-1");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("接收端返回非 2xx：仅告警，不阻断挂起")
    void non2xxResponseDoesNotThrow() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/hook",
                exchange -> {
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                });
        server.start();
        try {
            WebhookApprovalChannel channel =
                    new WebhookApprovalChannel(
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
            assertThatCode(() -> channel.notify(record())).doesNotThrowAnyException();
        } finally {
            server.stop(0);
        }
    }
}
