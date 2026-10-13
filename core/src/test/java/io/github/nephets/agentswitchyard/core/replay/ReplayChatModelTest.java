/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.replay;

import io.github.nephets.agentswitchyard.core.SwitchyardException;
import io.github.nephets.agentswitchyard.core.trace.W3CTraceCodec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ★ T02 验收标准 4：命中录制响应时<b>零上游调用</b>（WireMock verify(0) 断言）。 确定性来自响应注入，不是模型快照（ADR-0004）。
 *
 * <p>「上游模型」用 WireMock 模拟：委托 ChatModel 会真的发一个 HTTP 请求出去 —— 命中回放时它必须一次都没被调用。
 */
@DisplayName("ReplayChatModel（确定性 = 响应注入）")
class ReplayChatModelTest {

    private com.github.tomakehurst.wiremock.WireMockServer wireMock;

    @BeforeEach
    void setUp() {
        wireMock =
                new com.github.tomakehurst.wiremock.WireMockServer(wireMockConfig().dynamicPort());
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions"))
                        .willReturn(aResponse().withStatus(200).withBody("UPSTREAM-REPLY")));
        wireMock.start();
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    /** 委托模型：每次 call 都真实打一次 WireMock（代表「上游调用」）。 */
    private static final class UpstreamHttpChatModel implements ChatModel {

        private final String url;
        private final HttpClient client = HttpClient.newHttpClient();

        UpstreamHttpChatModel(String url) {
            this.url = url;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            try {
                HttpRequest request =
                        HttpRequest.newBuilder()
                                .uri(URI.create(url))
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                ReplayChatModel.promptText(prompt)))
                                .build();
                HttpResponse<String> response =
                        client.send(request, HttpResponse.BodyHandlers.ofString());
                return new ChatResponse(
                        List.of(
                                new Generation(
                                        new org.springframework.ai.chat.messages.AssistantMessage(
                                                response.body()))),
                        ChatResponseMetadata.builder().usage(new DefaultUsage(1, 1)).build());
            } catch (Exception ex) {
                throw new IllegalStateException("上游调用失败", ex);
            }
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return null;
        }
    }

    @Test
    @DisplayName("★ 命中录制响应 → 返回录制值且 WireMock verify(0)：零上游调用")
    void hitReturnsRecordedWithoutUpstreamCall() {
        String promptText = "对申请人 ORD-9931 做初筛";
        String digest = W3CTraceCodec.digest(promptText);
        ReplayChatModel replayModel =
                new ReplayChatModel(
                        Map.of(digest, "初筛通过：申请人资质正常"),
                        new UpstreamHttpChatModel(wireMock.baseUrl() + "/v1/chat/completions"));

        ChatResponse response = replayModel.call(new Prompt(new UserMessage(promptText)));

        assertThat(response.getResult().getOutput().getText()).isEqualTo("初筛通过：申请人资质正常");
        assertThat(response.getMetadata().getUsage().getTotalTokens()).isZero();
        // ★ 决定性断言：整个测试过程 WireMock 没有收到任何请求
        wireMock.verify(0, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    @DisplayName("严格回放：未命中且无委托 → SW_MODEL_UNAVAILABLE，绝不静默调真实模型")
    void missWithoutDelegateThrows() {
        ReplayChatModel replayModel = new ReplayChatModel(Map.of(), null);
        assertThatThrownBy(() -> replayModel.call(new Prompt(new UserMessage("没录过的输入"))))
                .isInstanceOf(SwitchyardException.class)
                .hasMessageContaining("未命中");
        wireMock.verify(0, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    @DisplayName("未命中但有委托 → 委托真实调用（学习/录制模式），且真实调用了 1 次")
    void missDelegatesToRealModel() {
        ReplayChatModel replayModel =
                new ReplayChatModel(
                        Map.of(),
                        new UpstreamHttpChatModel(wireMock.baseUrl() + "/v1/chat/completions"));
        ChatResponse response = replayModel.call(new Prompt(new UserMessage("没录过的输入")));

        assertThat(response.getResult().getOutput().getText()).isEqualTo("UPSTREAM-REPLY");
        wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    @DisplayName("ReplayRecorder 录制 → ReplayChatModel 命中；摘要格式契约")
    void recorderFeedsReplayModel() {
        InMemoryReplayStore store = new InMemoryReplayStore();
        ReplayRecorder recorder = new ReplayRecorder(store);
        String digest = W3CTraceCodec.digest("prompt-A");
        recorder.record(
                "inst-1",
                "span-1",
                0,
                "mock-chat",
                digest,
                "{\"text\":\"录制回复\",\"inputTokens\":3,\"outputTokens\":4}");

        assertThat(recorder.listBlobs("inst-1")).hasSize(1);
        assertThat(new ReplayChatModel(store, "inst-1").hasRecording(digest)).isTrue();
        assertThat(recorder.listBlobs("other-instance")).isEmpty();

        ReplayBlob blob = recorder.listBlobs("inst-1").get(0);
        assertThat(blob.spanId()).isEqualTo("span-1");
        assertThat(blob.requestDigest()).startsWith("sha256:");

        ChatResponse hit =
                new ReplayChatModel(store, "inst-1").call(new Prompt(new UserMessage("prompt-A")));
        assertThat(hit.getResult().getOutput().getText()).isEqualTo("录制回复");
        assertThat(hit.getMetadata().getUsage().getTotalTokens()).isEqualTo(7L);
    }

    @Test
    @DisplayName("ReplayBlob fail-fast：缺关键字段即拒绝")
    void blobValidation() {
        assertThatThrownBy(
                        () ->
                                new ReplayBlob(
                                        "inst-1",
                                        "span-1",
                                        0,
                                        "m",
                                        null,
                                        "{}",
                                        java.time.Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestDigest");
    }
}
