/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.mockllm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import reactor.core.publisher.Mono;

/**
 * OpenAI 兼容端点：/v1/chat/completions（非流式）+ /__admin/health。
 *
 * <p>★ 确定性：内容 = 输入摘要的纯函数（同样输入永远同样输出），usage 按 token 长度规则计算 —— 回放对比、幂等验证、成本测试都依赖这一点。
 */
@RestController
public class MockLlmController {

    private static final Logger LOG = LoggerFactory.getLogger(MockLlmController.class);

    /** OpenAI 风格聊天请求（只取需要的字段，多余字段忽略）。 */
    public record ChatCompletionRequest(String model, List<Message> messages, boolean stream) {

        /** 消息。 */
        public record Message(String role, String content) {}
    }

    /**
     * 聊天补全（OpenAI 兼容 schema）。
     *
     * <p>确定性规则：
     *
     * <ul>
     *   <li>content = {@code MOCK(<输入摘要前 12 位>)}
     *   <li>prompt_tokens = 末条用户消息长度，completion_tokens = 32，total 由本服务重算
     * </ul>
     */
    @PostMapping(
            value = "/v1/chat/completions",
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> chatCompletions(@RequestBody ChatCompletionRequest request) {
        LOG.info(
                "chat/completions 入口 model={} 消息数={}",
                request.model(),
                request.messages() == null ? 0 : request.messages().size());
        String lastUser =
                request.messages() == null
                        ? ""
                        : request.messages().stream()
                                .filter(m -> "user".equalsIgnoreCase(m.role()))
                                .reduce((a, b) -> b)
                                .map(ChatCompletionRequest.Message::content)
                                .orElse("");
        int promptTokens = lastUser.length();
        int completionTokens = 32;
        String content = "MOCK(" + sha256(lastUser).substring(0, 12) + ")";
        return Mono.just(
                Map.of(
                        "id",
                        "chatcmpl-mock-" + sha256(lastUser).substring(0, 8),
                        "object",
                        "chat.completion",
                        "created",
                        Instant.now().getEpochSecond(),
                        "model",
                        request.model() == null ? "mock-gpt" : request.model(),
                        "choices",
                        List.of(
                                Map.of(
                                        "index",
                                        0,
                                        "message",
                                        Map.of("role", "assistant", "content", content),
                                        "finish_reason",
                                        "stop")),
                        "usage",
                        Map.of(
                                "prompt_tokens", promptTokens,
                                "completion_tokens", completionTokens,
                                "total_tokens", promptTokens + completionTokens)));
    }

    /** 管理端健康检查（compose 的 healthcheck 目标）。 */
    @GetMapping(value = "/__admin/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> health() {
        LOG.debug("health 健康检查");
        return Mono.just(Map.of("status", "UP"));
    }

    private static String sha256(String payload) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            LOG.error("SHA-256 不可用", ex);
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
