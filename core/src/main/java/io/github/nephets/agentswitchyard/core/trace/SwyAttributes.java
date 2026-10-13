/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.trace;

import io.github.nephets.agentswitchyard.core.definition.StepKind;
import io.github.nephets.agentswitchyard.core.instance.StepRecord;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自有 span 属性名（契约 SPN-001）：全家族统一前缀 {@code swy.}，禁止裸自定义属性名。
 *
 * <p>★ 本仓独占写入：{@code swy.flow.instance.id} / {@code swy.step.*} / {@code swy.approval.state}。
 * 属性名只在本类出现一次 —— 改名只需改这里，契约测试会把不一致挡在 CI。
 *
 * <p>按 SPN-001 输出这些属性后，仓库③（llm-glasshouse）可以无损接收本仓的执行 span —— 不产生对 ③ 的 Maven 依赖（决策 4）。
 */
public final class SwyAttributes {

    /** 三仓共用。 */
    public static final String TENANT_ID = "swy.tenant.id";

    public static final String BIZ_LINE = "swy.biz.line";
    public static final String FEATURE = "swy.feature";
    public static final String PROMPT_VERSION = "swy.prompt.version";

    /** ②③ 共用。 */
    public static final String AGENT_ID = "swy.agent.id";

    public static final String AGENT_VERSION = "swy.agent.version";

    /** 本仓独占（SPN-001 登记 in 仅含 agentswitchyard）。 */
    public static final String FLOW_INSTANCE_ID = "swy.flow.instance.id";

    public static final String STEP_ID = "swy.step.id";
    public static final String STEP_SEQ = "swy.step.seq";
    public static final String STEP_KIND = "swy.step.kind";
    public static final String APPROVAL_STATE = "swy.approval.state";

    /** ①③ 共用（②的每步成本走同一口径）。 */
    public static final String USAGE_SOURCE = "swy.usage.source";

    public static final String COST_AMOUNT = "swy.cost.amount";
    public static final String COST_CURRENCY = "swy.cost.currency";
    public static final String PRICE_SOURCE = "swy.price.source";

    /** 三仓共用。 */
    public static final String PAYLOAD_DIGEST = "swy.payload.digest";

    public static final String PAYLOAD_FULL = "swy.payload.full";

    private SwyAttributes() {
        // 常量类不允许实例化
    }

    /**
     * 把一个步骤记录转成 SPN-001 的 span 属性（供 ③ 接收，也供 JSONL 导出）。
     *
     * @param agentId Agent 标识
     * @param agentVersion Agent 版本
     * @param tenantId 租户（可 null）
     * @param step 步骤记录
     * @return 属性表（保持声明顺序，便于快照对比）
     */
    public static Map<String, Object> forStep(
            String agentId, String agentVersion, String tenantId, StepRecord step) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        putIfNotNull(attrs, TENANT_ID, tenantId);
        putIfNotNull(attrs, AGENT_ID, agentId);
        putIfNotNull(attrs, AGENT_VERSION, agentVersion);
        attrs.put(FLOW_INSTANCE_ID, step.flowInstanceId());
        attrs.put(STEP_ID, step.stepId());
        attrs.put(STEP_SEQ, step.seq());
        attrs.put(STEP_KIND, step.kind().name());
        if (step.usage() != null) {
            attrs.put(USAGE_SOURCE, step.usage().usageSource().name());
        }
        attrs.put(COST_AMOUNT, step.costCny() == null ? 0.0d : step.costCny().doubleValue());
        attrs.put(COST_CURRENCY, "CNY");
        attrs.put(PAYLOAD_DIGEST, W3CTraceCodec.digest(step.outputJson()));
        return attrs;
    }

    /**
     * SPN-001 的 span 命名（{@code {operation} {target}}，单个空格，无引号）。
     *
     * @param kind 步骤类型
     * @param target 目标（模型 ID / 工具名 / 步骤 ID）
     * @return span 名
     */
    public static String spanName(StepKind kind, String target) {
        return switch (kind) {
            case LLM -> "chat " + target;
            case TOOL -> "execute_tool " + target;
            case HUMAN -> "approval " + target;
            case COMPENSATE -> "compensate " + target;
            case RULE -> "invoke_agent " + target;
        };
    }

    private static void putIfNotNull(Map<String, Object> attrs, String key, Object value) {
        // 契约 EVT-001：缺失维度必须保持缺失（null 不写属性），禁止填 "unknown"
        if (value != null) {
            attrs.put(key, value);
        }
    }
}
