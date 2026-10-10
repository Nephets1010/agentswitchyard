/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.tool;

/**
 * 工具风险等级（跨三仓字面一致，见架构 §8.2）。
 *
 * <p>★ {@code HIGH / CRITICAL} 且未声明 {@code requiresApproval=true} 的工具 <b>在注册时即被拒绝</b>（不是运行时才报错）——
 * 这是对抗 Prompt 注入的第三道闸（架构风险 6）。
 */
public enum RiskLevel {

    /** 只读 / 无副作用。 */
    LOW,

    /** 有内部副作用（写本地缓存 / 内部状态）。 */
    MEDIUM,

    /** 有外部副作用（写库 / 发消息）。 */
    HIGH,

    /** 有资金 / 安全级外部副作用（放款 / 删数据）。 */
    CRITICAL;

    /** 是否必须声明审批。 */
    public boolean requiresApprovalDeclaration() {
        return this == HIGH || this == CRITICAL;
    }
}
