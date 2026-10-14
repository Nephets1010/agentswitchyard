/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.store.support;

import com.fasterxml.jackson.core.type.TypeReference;

import io.github.nephets.agentswitchyard.core.Json;
import io.github.nephets.agentswitchyard.core.usage.Usage;
import io.github.nephets.agentswitchyard.core.usage.UsageSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

/**
 * 存储层编解码辅助：Usage ↔ JSON、Instant ↔ TIMESTAMP、输入 Map ↔ JSON。
 *
 * <p>统一走 core 的 {@link Json}（序列化字节级一致是回放对比的前提）。
 */
public final class StoreCodec {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private StoreCodec() {
        // 工具类不允许实例化
    }

    /** Usage 序列化（Usage 不可为 null，直接写）。 */
    public static String writeUsage(Usage usage) {
        return Json.write(usage);
    }

    /** Usage 反序列化；空值兜底为全 0 估算用量（坏行不应让整条账本读不出来）。 */
    public static Usage readUsage(String json) {
        if (json == null || json.isBlank()) {
            return Usage.estimated(UsageSource.FALLBACK, false);
        }
        try {
            return Json.read(json, Usage.class);
        } catch (RuntimeException ex) {
            return Usage.estimated(UsageSource.FALLBACK, false);
        }
    }

    /** 输入 Map 反序列化；空值返回空 Map。 */
    public static Map<String, Object> readInputMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return Json.mapper().readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            throw new IllegalArgumentException("input_json 反序列化失败", ex);
        }
    }

    /** Instant → TIMESTAMP 绑定值（null 安全）。 */
    public static Timestamp toTimestamp(Instant at) {
        return at == null ? null : Timestamp.from(at);
    }

    /** TIMESTAMP → Instant（null 安全）。 */
    public static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
