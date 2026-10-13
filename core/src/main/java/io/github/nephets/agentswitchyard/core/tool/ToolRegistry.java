/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.tool;

import io.github.nephets.agentswitchyard.core.ErrorCode;
import io.github.nephets.agentswitchyard.core.SwitchyardException;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表：Agent 只能调用<b>显式注册</b>的工具。
 *
 * <p>★ 第一道闸（架构风险 6 对策）：Agent 无法动态发现新工具 —— 注册表是白名单，不在表里的一律 {@code SW_TOOL_DENIED}。
 */
public final class ToolRegistry {

    private final Map<String, ToolDescriptor> tools = new ConcurrentHashMap<>();

    /**
     * 注册工具（重名重复注册即拒绝：工具治理要的是「声明过的确定性」，不是「最后一个赢」）。
     *
     * @param descriptor 工具描述符
     * @throws SwitchyardException 重名（SW_BAD_REQUEST）；描述符自身的注册期安全闸异常直接透传
     */
    public void register(ToolDescriptor descriptor) {
        ToolDescriptor existing = tools.putIfAbsent(descriptor.toolName(), descriptor);
        if (existing != null) {
            throw new SwitchyardException(
                    ErrorCode.SW_BAD_REQUEST,
                    "工具 " + descriptor.toolName() + " 已注册（不允许覆盖注册，如需变更请先显式下线）",
                    null);
        }
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
