/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.chat;

import io.github.nephets.agentswitchyard.core.instance.StepContext;

/**
 * LLM 网关（core SPI）：handler 调模型的唯一通道。
 *
 * <p>生产实现（starter 模块）包装 Spring AI 的 {@code ChatModel}，并负责：
 *
 * <ul>
 *   <li>录制模式：把响应按 {@code (instanceId, spanId, seq)} 落回放 blob
 *   <li>回放模式：命中录制响应直接返回，<b>零上游调用</b>（确定性回放的全部秘密，见 ADR-0004）
 * </ul>
 */
@FunctionalInterface
public interface ChatGateway {

    /**
     * 调用模型。
     *
     * @param context 步骤上下文（提供实例 ID / spanId / 序号）
     * @param modelId 模型标识（进 SPN-001 span 命名：{@code chat {modelId}}）
     * @param prompt 提示词
     * @return 模型回复
     */
    ChatReply chat(StepContext context, String modelId, String prompt);
}
