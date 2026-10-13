/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * core 层共享的 Jackson {@link ObjectMapper}。
 *
 * <p>统一配置的目的：执行轨迹 JSONL、回放 blob、消息信封的序列化在所有模块里产生 <b>字节级一致</b>的结果——回放对比（TraceDiff）依赖这一点。各模块不要自己 new
 * ObjectMapper。
 */
public final class Json {

    private static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    // 时间戳统一 ISO-8601 字符串（契约 EVT-001 要求毫秒精度 ISO-8601 UTC）
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .disable(SerializationFeature.WRITE_DATE_KEYS_AS_TIMESTAMPS);

    private Json() {
        // 工具类不允许实例化
    }

    /** 共享实例。线程安全（ObjectMapper 配置完成后是线程安全的）。 */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** 序列化为紧凑 JSON；失败抛 {@link IllegalStateException}（序列化失败属于编程错误）。 */
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("JSON 序列化失败: " + value, ex);
        }
    }

    /** 反序列化；失败抛 {@link IllegalArgumentException}（坏数据要 fail-fast 且可读）。 */
    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception ex) {
            throw new IllegalArgumentException("JSON 反序列化失败（期望 " + type.getSimpleName() + "）", ex);
        }
    }
}
