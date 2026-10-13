# ADR-0001：core 层零 Spring 依赖（单一例外：Spring AI 模型 SPI）

- 状态：已接受
- 日期：2026-10-10

## 背景

架构 §5 要求 core 层「零 Spring 依赖」，同时 §5.5 / §7.4 要求 `ReplayChatModel implements ChatModel`（Spring AI 的模型 SPI）。二者字面冲突：Spring AI 1.1.8 的 `org.springframework.ai.chat.model.ChatModel` 就是框架接口。

## 决策

1. core 的 pom 仅依赖 `spring-ai-client-chat`（模型 SPI 薄 jar），不引 spring-boot / spring-web / servlet；
2. maven-enforcer 的 bannedDependencies 在 core 模块强制封禁 `org.springframework.boot:*`、`org.springframework:spring-web*`、`jakarta.servlet:*`；
3. CI 的架构边界 job 对 core 做 grep 检查，import 白名单中 `org.springframework.ai` 是唯一例外；
4. 「零 Spring 依赖」的解释收敛为：**零 Spring Boot / Web / Servlet 依赖**（ADR-0001 即本决策的记录）。

## 后果

- core 可脱离容器单测（180+ 个纯 JUnit 测试，毫秒级）；
- FlowExecutor / 幂等 / 审批 / 补偿逻辑可以嵌入任何 JVM 进程（Netty 后端、Flink job、CLI）；
- 代价：core 里不能直接用 Spring 的事件 / 事务抽象，需要通过 SPI（StepStore / ApprovalStore / ChatGateway）反向解耦 —— 这正是我们要的。
