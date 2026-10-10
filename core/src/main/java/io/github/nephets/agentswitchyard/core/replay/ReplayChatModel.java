/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.replay;

import io.github.nephets.agentswitchyard.core.ErrorCode;
import io.github.nephets.agentswitchyard.core.Json;
import io.github.nephets.agentswitchyard.core.SwitchyardException;
import io.github.nephets.agentswitchyard.core.trace.W3CTraceCodec;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 回放用 ChatModel：命中录制响应即返回，<b>零上游调用</b>。
 *
 * <p>★ 这是「确定性回放」的技术核心（ADR-0004）：
 *
 * <ul>
 *   <li>命中判定 = 请求摘要（prompt 的 sha256）：同输入必命中，不同输入不误命中
 *   <li>命中后返回<b>录制时的原文与计量</b>，不产生任何网络调用（测试用 WireMock verify(0) 证明）
 *   <li>未命中：严格回放模式（delegate=null）直接失败——「回放不完整」必须显式暴露， 静默降级到真实调用会让「100% 确定性」变成谎言
 * </ul>
 *
 * <p>文档必须写明：确定性来自<b>响应注入</b>，不是模型快照——模型快照无法冻结输出。
 */
public final class ReplayChatModel implements ChatModel {

    /** 录制的响应（key = requestDigest）。 */
    private final Map<String, RecordedResponse> responsesByDigest;

    /** 委托模型（仅供「学习模式」；严格回放模式为 null）。 */
    private final ChatModel delegate;

    /** 录制响应的 JSON 载荷。 */
    public record RecordedResponse(String text, long inputTokens, long outputTokens) {}

    /**
     * 严格回放模式：命中即返回录制值，未命中抛异常。
     *
     * @param store 回放存储
     * @param instanceId 流程实例 ID（只加载该实例的录制）
     */
    public ReplayChatModel(ReplayStore store, String instanceId) {
        this(store, instanceId, null);
    }

    /**
     * 带委托的回放模式：未命中时委托给真实模型（学习/录制模式用）。
     *
     * @param store 回放存储
     * @param instanceId 流程实例 ID
     * @param delegate 真实模型（可为 null = 严格回放）
     */
    public ReplayChatModel(ReplayStore store, String instanceId, ChatModel delegate) {
        Map<String, RecordedResponse> map = new HashMap<>();
        for (ReplayBlob blob : store.listBlobs(instanceId)) {
            map.put(blob.requestDigest(), Json.read(blob.responseJson(), RecordedResponse.class));
        }
        this.responsesByDigest = Map.copyOf(map);
        this.delegate = delegate;
    }

    /**
     * 测试便捷构造：直接以「摘要 → 文本」映射构建。
     *
     * @param responses 摘要 → 响应文本
     * @param delegate 委托模型（可为 null）
     */
    public ReplayChatModel(Map<String, String> responses, ChatModel delegate) {
        Map<String, RecordedResponse> map = new HashMap<>();
        responses.forEach((digest, text) -> map.put(digest, new RecordedResponse(text, 0, 0)));
        this.responsesByDigest = Map.copyOf(map);
        this.delegate = delegate;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String digest = W3CTraceCodec.digest(promptText(prompt));
        RecordedResponse recorded = responsesByDigest.get(digest);
        if (recorded != null) {
            return toChatResponse(recorded);
        }
        if (delegate == null) {
            throw new SwitchyardException(
                    ErrorCode.SW_MODEL_UNAVAILABLE,
                    "确定性回放未命中（digest=" + digest + "）且未配置委托模型：" + "严格回放模式下不允许静默调用真实模型",
                    null);
        }
        return delegate.call(prompt);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate == null ? null : delegate.getDefaultOptions();
    }

    /** 是否已有某摘要的录制。 */
    public boolean hasRecording(String digest) {
        return responsesByDigest.containsKey(digest);
    }

    private static ChatResponse toChatResponse(RecordedResponse recorded) {
        Generation generation = new Generation(new AssistantMessage(recorded.text()));
        DefaultUsage usage =
                new DefaultUsage((int) recorded.inputTokens(), (int) recorded.outputTokens());
        ChatResponseMetadata metadata = ChatResponseMetadata.builder().usage(usage).build();
        return new ChatResponse(List.of(generation), metadata);
    }

    /** 提取 prompt 文本（user/system 消息文本拼接；录制与回放用同一实现保证摘要一致）。 */
    public static String promptText(Prompt prompt) {
        if (prompt == null || prompt.getInstructions() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        prompt.getInstructions()
                .forEach(
                        message -> {
                            if (sb.length() > 0) {
                                sb.append('\n');
                            }
                            sb.append(message.getText() == null ? "" : message.getText());
                        });
        return sb.toString();
    }
}
