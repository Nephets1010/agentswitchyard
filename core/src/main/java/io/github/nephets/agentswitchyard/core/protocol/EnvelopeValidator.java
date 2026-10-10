/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;

import io.github.nephets.agentswitchyard.core.Json;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 信封校验器：双层校验（结构层 = JSON Schema；语义层 = 字段语义规则）。
 *
 * <p>非法消息被拒绝且错误<b>可读</b>（含字段名与原因），这是协议公开可读的信任基础。
 */
public final class EnvelopeValidator {

    private final JsonSchema schema;

    /** 创建校验器（加载 classpath 的 agent-envelope-v1.schema.json）。 */
    public EnvelopeValidator() {
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().build();
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        try (InputStream in =
                EnvelopeValidator.class.getResourceAsStream(
                        "/protocol/agent-envelope-v1.schema.json")) {
            if (in == null) {
                throw new IllegalStateException(
                        "classpath 缺少 protocol/agent-envelope-v1.schema.json");
            }
            // networknt 1.5.x：用 JsonNode 重载（byte[]/InputStream 重载只接受 String / SchemaLocation）
            this.schema = factory.getSchema(Json.mapper().readTree(in.readAllBytes()), config);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("协议 Schema 读取失败", ex);
        }
    }

    /**
     * 校验信封对象（语义层）。
     *
     * @param envelope 信封
     * @return 校验结果（错误含字段名与原因）
     */
    public ValidationResult validate(AgentEnvelope envelope) {
        List<String> errors = new ArrayList<>();
        if (envelope == null) {
            return ValidationResult.fail(List.of("envelope: 信封不可为 null"));
        }
        ProtocolVersion version = envelope.version();
        if (version == null) {
            errors.add(
                    "protocolVersion: 不支持的协议版本 ["
                            + envelope.protocolVersion()
                            + "]（受支持: "
                            + ProtocolVersion.V1.wireValue()
                            + "）");
        }
        requireText(errors, "messageId", envelope.messageId());
        requireText(errors, "sender", envelope.sender());
        requireText(errors, "receiver", envelope.receiver());
        requireText(errors, "intent", envelope.intent());
        requireText(errors, "idemKey", envelope.idemKey());
        if (envelope.sentAt() == null) {
            errors.add("sentAt: 不可为空（时间戳是跨 Agent 排序与审计的依据）");
        }
        if (envelope.traceId() == null || envelope.traceId().isBlank()) {
            errors.add("traceId: 不可为空（跨 Agent 链路必须可归并到同一 trace）");
        } else if (!envelope.traceId().matches("^[0-9a-f]{32}$")) {
            errors.add(
                    "traceId: 必须是 32 位小写 hex（W3C TraceContext），实际为 [" + envelope.traceId() + "]");
        }
        return ValidationResult.fail(errors);
    }

    /**
     * 校验 JSON 字符串（结构层 = JSON Schema + 语义层）。
     *
     * @param json 信封 JSON
     * @return 校验结果（Schema 错误转译为可读中文 + 字段名）
     */
    public ValidationResult validateJson(String json) {
        List<String> errors = new ArrayList<>();
        JsonNode node;
        try {
            node = Json.mapper().readTree(json);
        } catch (Exception ex) {
            return ValidationResult.fail(List.of("payload: 不是合法 JSON（" + ex.getMessage() + "）"));
        }
        Set<com.networknt.schema.ValidationMessage> violations = schema.validate(node);
        for (com.networknt.schema.ValidationMessage v : violations) {
            errors.add(readable(v));
        }
        ValidationResult structural = ValidationResult.fail(errors);
        if (!structural.valid()) {
            return structural;
        }
        return validate(Json.read(json, AgentEnvelope.class));
    }

    /** networknt 的校验消息自带「$.字段: 原因」结构，直接透传即满足「可读错误（含字段名与原因）」。 */
    private static String readable(com.networknt.schema.ValidationMessage violation) {
        return violation.getMessage();
    }

    private static void requireText(List<String> errors, String field, String value) {
        if (value == null || value.isBlank()) {
            errors.add(field + ": 不可为空");
        }
    }
}
