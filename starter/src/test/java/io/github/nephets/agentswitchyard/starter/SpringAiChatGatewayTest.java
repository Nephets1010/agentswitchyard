/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.starter;

import io.github.nephets.agentswitchyard.core.chat.ChatReply;
import io.github.nephets.agentswitchyard.core.instance.StepContext;
import io.github.nephets.agentswitchyard.core.replay.InMemoryReplayStore;
import io.github.nephets.agentswitchyard.core.replay.ReplayBlob;
import io.github.nephets.agentswitchyard.core.replay.ReplayChatModel;
import io.github.nephets.agentswitchyard.core.replay.ReplayRecorder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ SpringAiChatGateway：录制载荷形状与 core 的 ReplayChatModel.RecordedResponse 严格对齐 （回归守卫：曾经写过嵌套 usage
 * 导致回放静默拿到 null 文本）。
 */
@DisplayName("SpringAiChatGateway（录制对齐）")
class SpringAiChatGatewayTest {

    /** 桩模型：返回固定文本与 usage，不产生任何网络调用。 */
    private static final ChatModel STUB =
            new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    Generation generation = new Generation(new AssistantMessage("STUB-REPLY"));
                    ChatResponseMetadata metadata =
                            ChatResponseMetadata.builder().usage(new DefaultUsage(5, 7)).build();
                    return new ChatResponse(List.of(generation), metadata);
                }
            };

    private static StepContext context() {
        return new StepContext(
                "inst-gw",
                "flow-gw",
                "s1",
                0,
                "a".repeat(32),
                "span-gw-1",
                java.util.Map.of(),
                java.util.Map.of(),
                null);
    }

    @Test
    @DisplayName("录制 blob 可被 ReplayChatModel 命中：文本与计量与真实调用一致")
    void recordingRoundTripsWithReplayChatModel() {
        InMemoryReplayStore store = new InMemoryReplayStore();
        ReplayRecorder recorder = new ReplayRecorder(store);
        SpringAiChatGateway gateway = new SpringAiChatGateway(STUB, recorder);

        ChatReply reply = gateway.chat(context(), "stub-model", "提示词内容");
        assertThat(reply.text()).isEqualTo("STUB-REPLY");
        assertThat(reply.usage().inputTokens()).isEqualTo(5);
        assertThat(reply.usage().outputTokens()).isEqualTo(7);

        List<ReplayBlob> blobs = recorder.listBlobs("inst-gw");
        assertThat(blobs).hasSize(1);
        ReplayBlob blob = blobs.get(0);
        assertThat(blob.spanId()).isEqualTo("span-gw-1");
        assertThat(blob.seq()).isZero();
        assertThat(blob.modelId()).isEqualTo("stub-model");

        // ★ 回归守卫：录制的响应经 ReplayChatModel 注入后与原始调用完全一致
        ChatModel replayModel = new ReplayChatModel(store, "inst-gw");
        ChatResponse replayed = replayModel.call(new Prompt("提示词内容"));
        assertThat(replayed.getResult().getOutput().getText()).isEqualTo("STUB-REPLY");
        assertThat(replayed.getMetadata().getUsage().getPromptTokens()).isEqualTo(5);
        assertThat(replayed.getMetadata().getUsage().getCompletionTokens()).isEqualTo(7);
    }

    @Test
    @DisplayName("recorder 为 null：调用可用但不录制（回放不可用的降级形态）")
    void nullRecorderStillCalls() {
        SpringAiChatGateway gateway = new SpringAiChatGateway(STUB, null);
        ChatReply reply = gateway.chat(context(), "stub-model", "hi");
        assertThat(reply.text()).isEqualTo("STUB-REPLY");
    }

    @Test
    @DisplayName("同一步骤多次调用：seq 递增（一步可多次调模型）")
    void seqIncrementsPerSpan() {
        ReplayRecorder recorder = new ReplayRecorder(new InMemoryReplayStore());
        SpringAiChatGateway gateway = new SpringAiChatGateway(STUB, recorder);
        gateway.chat(context(), "stub-model", "第一次");
        gateway.chat(context(), "stub-model", "第二次");
        assertThat(recorder.listBlobs("inst-gw")).hasSize(2);
        assertThat(recorder.listBlobs("inst-gw").stream().map(ReplayBlob::seq).distinct().count())
                .isEqualTo(2);
    }
}
