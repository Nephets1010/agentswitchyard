/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.replay;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** ReplayStore 的纯 POJO 内存实现（架构 §8.3）。 */
public final class InMemoryReplayStore implements ReplayStore {

    private final Map<String, ReplayBlob> blobs = new ConcurrentHashMap<>();

    @Override
    public void save(ReplayBlob blob) {
        blobs.put(blob.instanceId() + ":" + blob.key(), blob);
    }

    @Override
    public Optional<ReplayBlob> findByDigest(String instanceId, String requestDigest) {
        return blobs.values().stream()
                .filter(b -> b.instanceId().equals(instanceId))
                .filter(b -> b.requestDigest().equals(requestDigest))
                .findFirst();
    }

    @Override
    public List<ReplayBlob> listBlobs(String instanceId) {
        return blobs.values().stream()
                .filter(b -> b.instanceId().equals(instanceId))
                .sorted(Comparator.comparing(ReplayBlob::spanId).thenComparingInt(ReplayBlob::seq))
                .collect(Collectors.toList());
    }
}
