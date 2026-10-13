/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.replay;

import java.util.List;

/**
 * 录制器：把模型调用的请求摘要与响应存入回放存储。
 *
 * <p>由 ChatGateway 的生产实现在每次真实模型调用后调用（见 starter 模块）。
 */
public final class ReplayRecorder {

    private final ReplayStore store;

    /** 创建录制器。 */
    public ReplayRecorder(ReplayStore store) {
        this.store = store;
    }

    /**
     * 录制一次模型调用。
     *
     * @param instanceId 流程实例 ID
     * @param spanId 步骤 spanId
     * @param seq 同步序号
     * @param modelId 模型标识
     * @param requestDigest 请求摘要（{@code W3CTraceCodec.digest(prompt)}）
     * @param responseJson 响应 JSON
     */
    public void record(
            String instanceId,
            String spanId,
            int seq,
            String modelId,
            String requestDigest,
            String responseJson) {
        store.save(
                new ReplayBlob(
                        instanceId,
                        spanId,
                        seq,
                        modelId,
                        requestDigest,
                        responseJson,
                        java.time.Instant.now()));
    }

    /**
     * 列出实例的全部录制记录。
     *
     * @param instanceId 流程实例 ID
     * @return 录制记录（spanId + seq 排序）
     */
    public List<ReplayBlob> listBlobs(String instanceId) {
        return store.listBlobs(instanceId);
    }

    /** 底层存储（ReplayChatModel 构建用）。 */
    public ReplayStore store() {
        return store;
    }
}
