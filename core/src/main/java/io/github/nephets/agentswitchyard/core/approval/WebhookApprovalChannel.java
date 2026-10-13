/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * Webhook 审批渠道：把审批待办 POST 到配置的 URL。
 *
 * <p>URL 未配置时降级为「仅日志」（本地 demo 不该被迫起一个接收端）。 通知失败<b>不阻断</b>挂起（审批记录已持久化，通知是尽力而为）。
 *
 * <p>★ 两个加固点：URL 必须是 http/https（否则 {@code file://} 之类协议会被照发，构成 SSRF 面， 在<b>构造期</b>
 * fail-fast，而不是等运行期发出去才发现）；正文走 {@link #sanitize} 的完整 JSON 转义。
 */
public final class WebhookApprovalChannel implements ApprovalChannel {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookApprovalChannel.class);

    private final String webhookUrl;
    private final HttpClient client;

    /** 创建仅日志渠道（webhook 未配置时）。 */
    public WebhookApprovalChannel() {
        this(null);
    }

    /**
     * 创建 webhook 渠道。
     *
     * @param webhookUrl 待办接收端 URL；null/blank 时仅记日志
     * @throws IllegalArgumentException 非 http/https 地址（含 {@code file://}、内网非 HTTP 协议）
     */
    public WebhookApprovalChannel(String webhookUrl) {
        // blank 归一为 null：保留「配了空串 = 未配置」的既有语义，不因空配置炸掉启动
        String normalized = webhookUrl == null || webhookUrl.isBlank() ? null : webhookUrl.trim();
        if (normalized != null && !isHttpUrl(normalized)) {
            throw new IllegalArgumentException("审批 webhook 必须是 http/https: " + webhookUrl);
        }
        this.webhookUrl = normalized;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public void notify(ApprovalRecord record) {
        String body =
                "{\"approvalId\":\""
                        + record.approvalId()
                        + "\",\"flowInstanceId\":\""
                        + record.flowInstanceId()
                        + "\",\"stepId\":\""
                        + record.stepId()
                        + "\",\"approverRole\":\""
                        + record.approverRole()
                        + "\",\"reason\":\""
                        + sanitize(record.reason())
                        + "\",\"dueAt\":"
                        + (record.dueAt() == null ? "null" : "\"" + record.dueAt() + "\"")
                        + "}";
        if (webhookUrl == null) {
            LOG.info("审批待办（未配置 webhook，仅日志）: {}", body);
            return;
        }
        try {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(webhookUrl))
                            .header("Content-Type", "application/json")
                            .header("X-Switchyard-Approval-Id", record.approvalId())
                            .timeout(Duration.ofSeconds(5))
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                LOG.warn(
                        "审批 webhook 返回非 2xx: {} approvalId={}",
                        response.statusCode(),
                        record.approvalId());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            LOG.warn("审批 webhook 发送被中断 approvalId={}", record.approvalId(), ex);
        } catch (Exception ex) {
            LOG.warn("审批 webhook 发送失败（不阻断挂起）approvalId={}", record.approvalId(), ex);
        }
    }

    /** http/https 判定（大小写不敏感：{@code HTTP://} 也是合法 scheme）。 */
    private static boolean isHttpUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /**
     * JSON 字符串转义（RFC 8259 §7）：反斜杠、引号、换行、回车、制表符与其余控制字符（U+0000–U+001F）全部转义。
     *
     * <p>★ 只替换引号 / 换行是不够的：reason 里的 {@code \} 或 {@code \t} 会产出非法 JSON，接收端解析失败，
     * 而本端只在 statusCode ≥ 300 才告警 —— 2xx 的坏 body 完全静默。
     *
     * @param value 原始值；null 视为空串
     * @return 可直接嵌入 JSON 双引号内的转义结果
     */
    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (ch < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
                    break;
            }
        }
        return escaped.toString();
    }
}
