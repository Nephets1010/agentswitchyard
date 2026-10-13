/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.starter;

import io.github.nephets.agentswitchyard.core.Json;
import io.github.nephets.agentswitchyard.core.chat.ChatGateway;
import io.github.nephets.agentswitchyard.core.chat.ChatReply;
import io.github.nephets.agentswitchyard.core.instance.StepContext;
import io.github.nephets.agentswitchyard.core.replay.ReplayRecorder;
import io.github.nephets.agentswitchyard.core.trace.W3CTraceCodec;
import io.github.nephets.agentswitchyard.core.usage.Usage;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 生产 LLM 网关：包装 Spring AI 的 {@code ChatModel}，并在录制模式下落回放 blob。
 *
 * <p>★ 每次真实模型调用后按 {@code (instanceId, spanId, seq)} 录制响应 —— 确定性回放的素材来源（ADR-0004：确定性来自响应注入，不是模型快照）。
 */
public class SpringAiChatGateway implements ChatGateway {

    private final java.util.function.Supplier<org.springframework.ai.chat.model.ChatModel>
            chatModelSupplier;
    private final ReplayRecorder recorder;
    private final Map<String, AtomicInteger> seqBySpan = new ConcurrentHashMap<>();

    /**
     * 创建网关（固定模型）。
     *
     * @param chatModel Spring AI 模型
     * @param recorder 录制器（可为 null = 不录制，回放不可用）
     */
    public SpringAiChatGateway(
            org.springframework.ai.chat.model.ChatModel chatModel, ReplayRecorder recorder) {
        this(() -> chatModel, recorder);
    }

    /**
     * 创建网关（惰性解析模型）。
     *
     * <p>★ 装配期拿不到 ChatModel 是常态：Spring AI 的 provider 自动装配可能晚于 starter 求值。 因此这里只存 supplier，真正调用时才解析。
     *
     * @param chatModelSupplier 模型提供者（返回 null = 当前没有可用模型）
     * @param recorder 录制器（可为 null = 不录制，回放不可用）
     */
    public SpringAiChatGateway(
            java.util.function.Supplier<org.springframework.ai.chat.model.ChatModel>
                    chatModelSupplier,
            ReplayRecorder recorder) {
        this.chatModelSupplier = chatModelSupplier;
        this.recorder = recorder;
    }

    /** 解析模型；没有可用模型时给出与「未装配网关」同样明确、可归因的报错。 */
    private org.springframework.ai.chat.model.ChatModel chatModel() {
        org.springframework.ai.chat.model.ChatModel model = chatModelSupplier.get();
        if (model == null) {
            throw new IllegalStateException(
                    "步骤调用了 chat()，但当前没有可用的 ChatModel bean"
                            + "（检查是否引入了 Spring AI provider starter，且 spring.ai.model.chat 指向了它；"
                            + "存在多个候选时请用 @Primary 指定）");
        }
        return model;
    }

    @Override
    public ChatReply chat(StepContext context, String modelId, String prompt) {
        ChatResponse response = chatModel().call(new Prompt(prompt));
        String text =
                response.getResult() == null || response.getResult().getOutput() == null
                        ? ""
                        : response.getResult().getOutput().getText();
        long inputTokens = 0;
        long outputTokens = 0;
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            Integer in = response.getMetadata().getUsage().getPromptTokens();
            Integer out = response.getMetadata().getUsage().getCompletionTokens();
            inputTokens = in == null ? 0 : in;
            outputTokens = out == null ? 0 : out;
        }
        if (recorder != null) {
            int seq =
                    seqBySpan
                            .computeIfAbsent(context.spanId(), k -> new AtomicInteger())
                            .getAndIncrement();
            // ★ 载荷形状与 core 的 ReplayChatModel.RecordedResponse 严格对齐（扁平三字段）
            String responseJson =
                    Json.write(
                            Map.of(
                                    "text",
                                    text,
                                    "inputTokens",
                                    inputTokens,
                                    "outputTokens",
                                    outputTokens));
            recorder.record(
                    context.flowInstanceId(),
                    context.spanId(),
                    seq,
                    modelId,
                    W3CTraceCodec.digest(prompt),
                    responseJson);
        }
        return new ChatReply(text, Usage.vendorReported(inputTokens, outputTokens));
    }
}
