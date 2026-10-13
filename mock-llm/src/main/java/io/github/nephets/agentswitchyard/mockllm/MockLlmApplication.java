/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.mockllm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * mock-llm：OpenAI 兼容的确定性 mock 模型服务（WebFlux）。
 *
 * <p>CI 与本地开发用 —— 零真实 API 调用、零成本（验收硬标准 11）： 同样的输入永远返回同样的输出与 usage，响应完全可预测。
 */
@SpringBootApplication
public class MockLlmApplication {

    /** 启动入口。 */
    public static void main(String[] args) {
        SpringApplication.run(MockLlmApplication.class, args);
    }
}
