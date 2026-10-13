/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * YAML 流程定义加载器：让非 Java 团队（运维 / 业务工程）也能声明流程。
 *
 * <p>schema 见 {@code samples/credit-approve.yaml}。加载即校验：任何字段错误都在加载期抛出， 不允许「跑起来才发现第 4 步没配审批角色」。
 */
public final class YamlFlowLoader {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private YamlFlowLoader() {
        // 工具类不允许实例化
    }

    /** 从字符串加载。 */
    public static FlowDefinition load(String yaml) {
        return parse(yaml);
    }

    /** 从文件加载。 */
    public static FlowDefinition load(Path file) {
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalArgumentException("无法读取流程定义文件: " + file, ex);
        }
    }

    /** 从 classpath 加载。 */
    public static FlowDefinition loadFromClasspath(String resource) {
        try (InputStream in = YamlFlowLoader.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("classpath 不存在流程定义: " + resource);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalArgumentException("无法读取流程定义资源: " + resource, ex);
        }
    }

    private static FlowDefinition parse(String yaml) {
        JsonNode root;
        try {
            root = YAML_MAPPER.readTree(yaml);
        } catch (IOException ex) {
            throw new IllegalArgumentException("YAML 解析失败：" + ex.getMessage(), ex);
        }
        String flowId = requireText(root, "flowId");
        int version = root.path("version").asInt(1);

        JsonNode steps = root.get("steps");
        if (steps == null || !steps.isArray() || steps.isEmpty()) {
            throw new IllegalArgumentException("流程 " + flowId + " 缺少 steps 数组");
        }
        // 逐个解析后经 FlowBuilder 重排 order，复用 FlowDefinition 的连续性校验
        FlowBuilder builder = new FlowBuilder(flowId).version(version);
        List<StepDefinition> parsed = new ArrayList<>();
        for (JsonNode node : steps) {
            parsed.add(parseStep(node, flowId));
        }
        FlowBuilder rebuilder = new FlowBuilder(flowId).version(version);
        parsed.forEach(rebuilder::step);
        return rebuilder.build();
    }

    private static StepDefinition parseStep(JsonNode node, String flowId) {
        String stepId = requireText(node, "stepId");
        StepKind kind;
        try {
            kind = StepKind.valueOf(requireText(node, "kind"));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "流程 "
                            + flowId
                            + " 步骤 "
                            + stepId
                            + " 的 kind 非法（可用值 LLM/TOOL/RULE/HUMAN/COMPENSATE）",
                    ex);
        }

        boolean requiresApproval = node.path("requiresApproval").asBoolean(false);
        String approvalRole =
                node.hasNonNull("approvalRole") ? node.get("approvalRole").asText() : null;
        String idempotencyKey =
                node.hasNonNull("idempotencyKey") ? node.get("idempotencyKey").asText() : null;
        String compensationRef =
                node.hasNonNull("compensation") ? node.get("compensation").asText() : null;
        long timeoutMs = node.path("timeoutMs").asLong(0);
        int maxAttempts = node.path("maxAttempts").asInt(1);
        String description =
                node.hasNonNull("description") ? node.get("description").asText() : null;

        return new StepDefinition(
                stepId,
                kind,
                0,
                idempotencyKey,
                requiresApproval,
                approvalRole,
                Duration.ofMillis(timeoutMs),
                maxAttempts > 1 ? RetrySpec.DEFAULT.withAttempts(maxAttempts) : RetrySpec.NO_RETRY,
                compensationRef == null
                        ? CompensationSpec.NONE
                        : CompensationSpec.with(compensationRef),
                description);
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.asText().isBlank()) {
            throw new IllegalArgumentException("缺少必填字段 " + field + "（节点: " + node + "）");
        }
        return value.asText();
    }
}
