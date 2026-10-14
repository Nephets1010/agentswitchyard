/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.protocol;

import java.time.Instant;
import java.util.UUID;

/**
 * 多 Agent 消息信封（PRD P0-6 / 架构 §5.5）：版本化的结构化消息。
 *
 * <p>首版为<b>单机信封</b>（JSON Schema 校验 + 公开协议页），A2A 网络传输在 P1（架构 §6.3 的 P0 范围判定）。协议 JSON Schema
 * 公开可读：{@code core/src/main/resources/protocol/agent-envelope-v1.schema.json}。
 *
 * @param protocolVersion 协议版本（{@code "1.0"}）
 * @param messageId 消息唯一 ID（UUID）
 * @param sender 发送方标识
 * @param receiver 接收方标识
 * @param intent 意图（接收方据此路由）
 * @param traceId W3C traceId（跨 Agent 链路归并到同一 trace）
 * @param idemKey 幂等键（重投递安全）
 * @param sentAt 发送时间
 * @param payload 业务载荷（结构由 intent 决定）
 */
public record AgentEnvelope(
        String protocolVersion,
        String messageId,
        String sender,
        String receiver,
        String intent,
        String traceId,
        String idemKey,
        Instant sentAt,
        Object payload) {

    /** 当前协议版本常量。 */
    public static final String CURRENT = ProtocolVersion.V1.wireValue();

    /** 紧凑构造器：null 归一（校验交给 EnvelopeValidator，构造器只兜底缺省）。 */
    public AgentEnvelope {
        protocolVersion =
                protocolVersion == null || protocolVersion.isBlank() ? CURRENT : protocolVersion;
        messageId =
                messageId == null || messageId.isBlank() ? UUID.randomUUID().toString() : messageId;
    }

    /** 解析协议版本；未知版本返回 null（由 Validator 拒绝）。 */
    public ProtocolVersion version() {
        for (ProtocolVersion v : ProtocolVersion.values()) {
            if (v.wireValue().equals(protocolVersion)) {
                return v;
            }
        }
        return null;
    }
}
