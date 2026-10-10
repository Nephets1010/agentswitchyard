/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.approval;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Webhook 审批渠道：把审批待办 POST 到配置的 URL。
 *
 * <p>URL 未配置时降级为「仅日志」（本地 demo 不该被迫起一个接收端）。 通知失败<b>不阻断</b>挂起（审批记录已持久化，通知是尽力而为）。
 */
public final class WebhookApprovalChannel implements ApprovalChannel {

    private static final Logger LOG = Logger.getLogger(WebhookApprovalChannel.class.getName());

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
     */
    public WebhookApprovalChannel(String webhookUrl) {
        this.webhookUrl = webhookUrl == null || webhookUrl.isBlank() ? null : webhookUrl;
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
            LOG.info("审批待办（未配置 webhook，仅日志）: " + body);
            return;
        }
        try {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(webhookUrl))
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(5))
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                LOG.warning(
                        "审批 webhook 返回非 2xx: "
                                + response.statusCode()
                                + " approvalId="
                                + record.approvalId());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            LOG.log(Level.WARNING, "审批 webhook 发送被中断 approvalId=" + record.approvalId(), ex);
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "审批 webhook 发送失败（不阻断挂起）approvalId=" + record.approvalId(), ex);
        }
    }

    /** 防止业务理由里的引号破坏 JSON（完整转义由 Jackson 在序列化路径上做，这里防御性兜底）。 */
    private static String sanitize(String value) {
        return value == null ? "" : value.replace("\"", "'").replace("\n", " ");
    }
}
