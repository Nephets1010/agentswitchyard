/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.protocol;

import java.util.Set;

/**
 * 消息协议版本（PRD P0-6：协议带版本号，非法版本被拒绝）。
 *
 * <p>兼容规则（T04 验收标准 2）：
 *
 * <ul>
 *   <li>接收方必须支持自己的版本与所有更低版本（v2 接收端可收 v1）
 *   <li>发送方只声明自己写入的版本
 *   <li>大版本变更（破坏性）必须升 MAJOR，此处 enum 追加新常量即可
 * </ul>
 */
public enum ProtocolVersion {

    /** 首版协议。 */
    V1("1.0");

    private final String wire;

    ProtocolVersion(String wire) {
        this.wire = wire;
    }

    /** 协议线上版本字符串（agent-envelope-v1.schema.json 的 enum 值）。 */
    public String wireValue() {
        return wire;
    }

    /**
     * 判断本版本是否支持接收指定版本的消息（低版本向前兼容）。
     *
     * @param other 消息声明的版本
     * @return true 表示可接收
     */
    public boolean supports(ProtocolVersion other) {
        return other != null && other.ordinal() <= this.ordinal();
    }

    /**
     * 按线上字符串解析版本；未知版本返回 empty（由 Validator 拒绝并给出可读错误）。
     *
     * @param wire 线上版本字符串
     * @return 版本；未知返回 Optional.empty()
     */
    public static Set<ProtocolVersion> all() {
        return Set.of(values());
    }
}
