/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.replay;

import java.util.List;
import java.util.Optional;

/** 回放 blob 存储 SPI（core 定义；JDBC 实现在 store 模块的 {@code TraceBlobStore}）。 */
public interface ReplayStore {

    /** 保存录制记录（幂等：同 (instanceId, spanId, seq) 重复保存覆盖）。 */
    void save(ReplayBlob blob);

    /** 按请求摘要查录制记录（回放命中判定）。 */
    Optional<ReplayBlob> findByDigest(String instanceId, String requestDigest);

    /** 实例的全部录制记录（按 spanId + seq 排序）。 */
    List<ReplayBlob> listBlobs(String instanceId);
}
