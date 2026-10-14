/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 消息协议契约（T04 验收标准 1/2）： 合法信封通过；非法消息（缺 traceId / 版本不支持 / receiver 为空）被拒绝且错误可读（含字段名与原因）。 */
@DisplayName("AgentEnvelope 契约（JSON Schema + 语义校验）")
class AgentEnvelopeContractTest {

    private final EnvelopeValidator validator = new EnvelopeValidator();

    private static String validJson() {
        return """
                {
                  "protocolVersion": "1.0",
                  "messageId": "11111111-2222-3333-4444-555555555555",
                  "sender": "credit-agent",
                  "receiver": "risk-agent",
                  "intent": "RISK_SCORE_REQUEST",
                  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
                  "idemKey": "ORD-9931:risk",
                  "sentAt": "2026-10-10T08:00:00Z",
                  "payload": {"ref": "ORD-9931"}
                }
                """;
    }

    private AgentEnvelope validEnvelope() {
        return new AgentEnvelope(
                ProtocolVersion.V1.wireValue(),
                "11111111-2222-3333-4444-555555555555",
                "credit-agent",
                "risk-agent",
                "RISK_SCORE_REQUEST",
                "4bf92f3577b34da6a3ce929d0e0e4736",
                "ORD-9931:risk",
                Instant.parse("2026-10-10T08:00:00Z"),
                java.util.Map.of("ref", "ORD-9931"));
    }

    @Test
    @DisplayName("合法信封：结构层（Schema）与语义层全部通过")
    void validEnvelopePassesBothLayers() {
        assertThat(validator.validateJson(validJson()).valid()).isTrue();
        assertThat(validator.validate(validEnvelope()).valid()).isTrue();
    }

    @Test
    @DisplayName("★ 缺 traceId 被拒绝，错误含字段名")
    void missingTraceIdRejected() {
        String json = validJson().replace("\"traceId\": \"4bf92f3577b34da6a3ce929d0e0e4736\",", "");
        ValidationResult result = validator.validateJson(json);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("traceId"));
    }

    @Test
    @DisplayName("★ 协议版本不支持被拒绝（Schema enum + 语义层双拦）")
    void unsupportedVersionRejected() {
        String json = validJson().replace("1.0", "9.9");
        ValidationResult result = validator.validateJson(json);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("protocolVersion"));
    }

    @Test
    @DisplayName("★ receiver 为空被拒绝，错误含字段名")
    void emptyReceiverRejected() {
        String json = validJson().replace("risk-agent", "");
        ValidationResult result = validator.validateJson(json);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("receiver"));
    }

    @Test
    @DisplayName("traceId 非 32 位小写 hex 被拒绝（W3C 规范）")
    void malformedTraceIdRejected() {
        AgentEnvelope envelope =
                new AgentEnvelope(
                        "1.0",
                        "m1",
                        "a",
                        "b",
                        "intent",
                        "NOT-A-TRACE-ID",
                        "key",
                        Instant.now(),
                        null);
        ValidationResult result = validator.validate(envelope);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("traceId") && e.contains("32 位"));
    }

    @Test
    @DisplayName("非 JSON 输入被拒绝且错误可读")
    void nonJsonRejected() {
        ValidationResult result = validator.validateJson("this is not json");
        assertThat(result.valid()).isFalse();
        assertThat(result.errors().get(0)).contains("不是合法 JSON");
    }

    @Test
    @DisplayName("版本兼容规则：V1 支持 V1；未知版本解析为 null")
    void versioningRules() {
        assertThat(ProtocolVersion.V1.supports(ProtocolVersion.V1)).isTrue();
        assertThat(ProtocolVersion.V1.supports(null)).isFalse();
        assertThat(
                        new AgentEnvelope(
                                        "3.0",
                                        "m",
                                        "a",
                                        "b",
                                        "i",
                                        "4bf92f3577b34da6a3ce929d0e0e4736",
                                        "k",
                                        Instant.now(),
                                        null)
                                .version())
                .isNull();
        assertThat(ProtocolVersion.V1.wireValue()).isEqualTo("1.0");
    }

    @Test
    @DisplayName("信封缺省：protocolVersion 与 messageId 自动补齐")
    void defaultsFilled() {
        AgentEnvelope envelope =
                new AgentEnvelope(
                        null,
                        null,
                        "a",
                        "b",
                        "i",
                        "4bf92f3577b34da6a3ce929d0e0e4736",
                        "k",
                        Instant.now(),
                        List.of(1));
        assertThat(envelope.protocolVersion()).isEqualTo("1.0");
        assertThat(envelope.messageId()).isNotBlank();
    }
}
