/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.sample;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * demo 内置确定性模型：同样输入永远同样输出（CI 零真实 API / 零成本，验收硬标准 11）。
 *
 * <p>业务接入真实模型时把 {@code spring.ai.model.chat} 设为 {@code openai} 并配置 {@code
 * spring.ai.openai.*}（sample 已带 openai starter 依赖），本 bean 由
 * {@code @ConditionalOnProperty(havingValue = "demo")} 自动让位。
 */
public class DemoChatModel implements ChatModel {

    @Override
    public ChatResponse call(Prompt prompt) {
        String text = prompt.getContents() == null ? "" : prompt.getContents();
        String content = "DEMO[" + sha256(text).substring(0, 12) + "]";
        Generation generation = new Generation(new AssistantMessage(content));
        ChatResponseMetadata metadata =
                ChatResponseMetadata.builder().usage(new DefaultUsage(12, 32)).build();
        return new ChatResponse(List.of(generation), metadata);
    }

    private static String sha256(String payload) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
