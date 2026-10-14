/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.instance;

import io.github.nephets.agentswitchyard.core.chat.ChatGateway;
import io.github.nephets.agentswitchyard.core.chat.ChatReply;

import java.util.Map;

/**
 * 步骤上下文：handler 与可靠性层之间唯一的交互面。
 *
 * <p>handler 通过 {@link #chat(String, String)} 调模型（回放时自动命中录制响应）， 通过 {@link #attribute(String)}
 * 感知执行模式（如 {@code replay=true} 时跳过真实副作用）。
 */
public final class StepContext {

    private final String flowInstanceId;
    private final String flowId;
    private final String stepId;
    private final int seq;
    private final String traceId;
    private final String spanId;
    private final Map<String, Object> input;
    private final Map<String, Object> attributes;
    private final ChatGateway chatGateway;

    /**
     * 创建上下文（由 FlowExecutor 调用，handler 不需要自己构造）。
     *
     * @param flowInstanceId 实例 ID
     * @param flowId 流程 ID
     * @param stepId 步骤 ID
     * @param seq 步骤序号
     * @param traceId traceId
     * @param spanId 本步 spanId
     * @param input 流程输入
     * @param attributes 执行模式属性
     * @param chatGateway LLM 网关（无 LLM 步骤的流程可为 null）
     */
    public StepContext(
            String flowInstanceId,
            String flowId,
            String stepId,
            int seq,
            String traceId,
            String spanId,
            Map<String, Object> input,
            Map<String, Object> attributes,
            ChatGateway chatGateway) {
        this.flowInstanceId = flowInstanceId;
        this.flowId = flowId;
        this.stepId = stepId;
        this.seq = seq;
        this.traceId = traceId;
        this.spanId = spanId;
        this.input = input == null ? Map.of() : Map.copyOf(input);
        this.attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        this.chatGateway = chatGateway;
    }

    /** 实例 ID。 */
    public String flowInstanceId() {
        return flowInstanceId;
    }

    /** 流程 ID。 */
    public String flowId() {
        return flowId;
    }

    /** 步骤 ID。 */
    public String stepId() {
        return stepId;
    }

    /** 步骤序号。 */
    public int seq() {
        return seq;
    }

    /** traceId。 */
    public String traceId() {
        return traceId;
    }

    /** 本步 spanId。 */
    public String spanId() {
        return spanId;
    }

    /** 流程输入。 */
    public Map<String, Object> input() {
        return input;
    }

    /** 执行模式属性（如 {@code replay}）。 */
    public Object attribute(String key) {
        return attributes.get(key);
    }

    /** 是否回放模式（回放不产生真实副作用，handler 应只重算输出）。 */
    public boolean replay() {
        return Boolean.TRUE.equals(attribute("replay"));
    }

    /** 流程输入的指定值。 */
    public Object input(String key) {
        return input.get(key);
    }

    /**
     * 调用模型（经 ChatGateway：录制模式下落回放 blob，回放模式下命中录制响应、零上游调用）。
     *
     * @param modelId 模型标识（进 SPN-001 span 命名）
     * @param prompt 提示词
     * @return 模型回复（文本 + 计量）
     * @throws IllegalStateException 本流程未装配 LLM 网关
     */
    public ChatReply chat(String modelId, String prompt) {
        if (chatGateway == null) {
            throw new IllegalStateException(
                    "步骤 " + stepId + " 调用了 chat()，但本流程未装配 ChatGateway（检查 starter 的 LLM 配置）");
        }
        return chatGateway.chat(this, modelId, prompt);
    }
}
