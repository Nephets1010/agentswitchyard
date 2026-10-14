/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.tool;

import io.github.nephets.agentswitchyard.core.ErrorCode;
import io.github.nephets.agentswitchyard.core.SwitchyardException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表：Agent 只能调用<b>显式注册</b>的工具。
 *
 * <p>★ 第一道闸（架构风险 6 对策）：Agent 无法动态发现新工具 —— 注册表是白名单，不在表里的一律 {@code SW_TOOL_DENIED}。
 *
 * <p>★ 存储有界：工具数有上限（默认 {@value #DEFAULT_MAX_TOOLS}）。超限时**拒绝注册并失败**，而不是静默淘汰 —— 工具从白名单里消失只会让 Agent 收到
 * {@code SW_TOOL_DENIED}，调用方无从判断是「没注册」还是「被挤掉了」， 排查成本更高。工具是显式声明的基础设施，超限应当时刻可见。
 */
public final class ToolRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ToolRegistry.class);

    /** 默认工具数上限。 */
    public static final int DEFAULT_MAX_TOOLS = 1_024;

    private final int maxTools;
    private final Map<String, ToolDescriptor> tools = new ConcurrentHashMap<>();

    /** 使用默认上限 {@value #DEFAULT_MAX_TOOLS}。 */
    public ToolRegistry() {
        this(DEFAULT_MAX_TOOLS);
    }

    /**
     * @param maxTools 工具数上限（必须 &gt; 0）
     * @throws IllegalArgumentException maxTools 非正
     */
    public ToolRegistry(int maxTools) {
        if (maxTools < 1) {
            throw new IllegalArgumentException("maxTools 必须 > 0: " + maxTools);
        }
        this.maxTools = maxTools;
    }

    /**
     * @return 工具数上限
     */
    public int maxTools() {
        return maxTools;
    }

    /**
     * 注册工具（重名重复注册即拒绝：工具治理要的是「声明过的确定性」，不是「最后一个赢」）。
     *
     * @param descriptor 工具描述符
     * @throws SwitchyardException 重名（SW_BAD_REQUEST）；描述符自身的注册期安全闸异常直接透传
     * @throws IllegalStateException 已达到工具数上限（拒绝注册，不静默淘汰）
     */
    public void register(ToolDescriptor descriptor) {
        ToolDescriptor existing = tools.putIfAbsent(descriptor.toolName(), descriptor);
        if (existing != null) {
            throw new SwitchyardException(
                    ErrorCode.SW_BAD_REQUEST,
                    "工具 " + descriptor.toolName() + " 已注册（不允许覆盖注册，如需变更请先显式下线）",
                    null);
        }
        if (tools.size() > maxTools) {
            // 回滚本次插入：白名单只增不减，超限必须可见，不能把已注册的工具悄悄挤掉
            tools.remove(descriptor.toolName());
            LOG.error(
                    "工具数超过上限 {}，已拒绝注册 {}（白名单超限请上调 maxTools，而不是静默淘汰）",
                    maxTools,
                    descriptor.toolName());
            throw new IllegalStateException(
                    "工具数超过上限 " + maxTools + "，已拒绝注册: " + descriptor.toolName());
        }
        LOG.debug("工具已注册 name={}（当前 {}/{}）", descriptor.toolName(), tools.size(), maxTools);
    }

    /** 查找工具。 */
    public Optional<ToolDescriptor> find(String toolName) {
        return Optional.ofNullable(toolName == null ? null : tools.get(toolName));
    }

    /** 已注册的工具名集合（工具清单页数据源）。 */
    public Set<String> registeredNames() {
        return Set.copyOf(tools.keySet());
    }

    /** 全部已注册描述符（按名称排序；工具清单页数据源）。 */
    public java.util.List<ToolDescriptor> all() {
        return tools.values().stream()
                .sorted(java.util.Comparator.comparing(ToolDescriptor::toolName))
                .toList();
    }
}
