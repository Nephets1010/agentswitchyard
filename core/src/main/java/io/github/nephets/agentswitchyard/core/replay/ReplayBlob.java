/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.replay;

import java.time.Instant;

/**
 * 回放 blob：一次模型调用的录制记录。
 *
 * <p>★ 确定性来自<b>响应注入</b>，不是模型快照（ADR-0004）：模型快照无法冻结输出 （同版本模型也会漂移），唯一能 100%
 * 重现「当时结果」的办法是把当时的响应录下来、回放时原样注入。
 *
 * @param instanceId 流程实例 ID
 * @param spanId 模型调用所在步骤的 spanId
 * @param seq 同一步骤内的调用序号（一步可多次调用）
 * @param modelId 模型标识
 * @param requestDigest 请求摘要（sha256:{hex}，按 prompt 规范化序列化计算）
 * @param responseJson 录制的响应（JSON：{text, usage{...}}）
 * @param recordedAt 录制时间
 */
public record ReplayBlob(
        String instanceId,
        String spanId,
        int seq,
        String modelId,
        String requestDigest,
        String responseJson,
        Instant recordedAt) {

    /** 紧凑构造器：fail-fast 校验关键键。 */
    public ReplayBlob {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("回放 blob 缺少 instanceId");
        }
        if (spanId == null || spanId.isBlank()) {
            throw new IllegalArgumentException("回放 blob 缺少 spanId");
        }
        if (requestDigest == null || requestDigest.isBlank()) {
            throw new IllegalArgumentException("回放 blob 缺少 requestDigest（无请求摘要无法保证回放对齐）");
        }
        if (responseJson == null || responseJson.isBlank()) {
            throw new IllegalArgumentException("回放 blob 缺少 responseJson");
        }
    }

    /** 回放查找键：spanId + seq。 */
    public String key() {
        return spanId + ":" + seq;
    }
}
