/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.sample;

import io.github.nephets.agentswitchyard.core.chat.ChatGateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ 自动装配顺序回归（接真实模型的用户路径）：
 *
 * <p>当 {@code spring.ai.model.chat=openai} 时，ChatModel 由 Spring AI 的 provider 自动装配创建。 若 starter
 * 的自动装配<b>先于</b> provider 求值，{@code @ConditionalOnBean(ChatModel)} 看不到 bean， 网关被静默跳过 → LLM 步骤抛「未装配
 * ChatGateway」。
 *
 * <p>本用例在 openai 档位下断言 ChatGateway bean 必须存在；修前失败、修后通过。 demo 模型（用户 bean，注册早于所有自动装配）不会暴露这个坑，故必须显式测
 * openai 档位。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.ai.model.chat=openai", "spring.ai.openai.api-key=test-key-not-used"})
@DisplayName("OpenAI 档位 ChatGateway 装配（自动装配顺序回归）")
class OpenAiChatGatewayWiringIT {

    @Autowired private ApplicationContext context;

    @Test
    @DisplayName("openai 档位：Spring AI provider 装配了 ChatModel，starter 必须装配 ChatGateway")
    void chatGatewayPresentWhenProviderAutoConfigured() {
        // 前置确认：provider 自动装配确实生效（否则本用例失去意义）
        assertThat(context.getBeanNamesForType(ChatModel.class))
                .as("openai 档位下 Spring AI 应提供 ChatModel bean")
                .isNotEmpty();
        assertThat(context.getBeanNamesForType(ChatGateway.class))
                .as("starter 必须装配 ChatGateway —— 自动装配顺序 bug 会让这个 bean 静默消失")
                .isNotEmpty();
        assertThat(context.getBean(ChatGateway.class)).isNotNull();
    }
}
