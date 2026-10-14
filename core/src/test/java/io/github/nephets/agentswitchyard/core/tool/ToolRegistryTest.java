/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for full license text.
 */
package io.github.nephets.agentswitchyard.core.tool;

import io.github.nephets.agentswitchyard.core.SwitchyardException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 工具注册表的容量上限（P4「存储有界」）：白名单超限必须可见，不能静默淘汰。 */
@DisplayName("ToolRegistry 容量上限")
class ToolRegistryTest {

    private static ToolDescriptor tool(String name) {
        return new ToolDescriptor(
                name, "描述", Duration.ofSeconds(5), 0, true, false, Set.of(), RiskLevel.LOW);
    }

    @Test
    @DisplayName("默认上限为正且可查询")
    void 默认上限为正且可查询() {
        assertThat(new ToolRegistry().maxTools()).isPositive();
    }

    @Test
    @DisplayName("非正上限被拒")
    void 非正上限被拒() {
        assertThatThrownBy(() -> new ToolRegistry(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolRegistry(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("容量刚好占满时允许注册")
    void 容量刚好占满允许() {
        ToolRegistry registry = new ToolRegistry(3);
        registry.register(tool("a"));
        registry.register(tool("b"));
        registry.register(tool("c"));
        assertThat(registry.registeredNames()).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    @DisplayName("超限被拒，且不静默淘汰已注册工具、不混入超限工具")
    void 超限被拒且不静默淘汰() {
        ToolRegistry registry = new ToolRegistry(2);
        registry.register(tool("a"));
        registry.register(tool("b"));

        assertThatThrownBy(() -> registry.register(tool("c")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("上限");

        assertThat(registry.registeredNames()).containsExactlyInAnyOrder("a", "b");
        assertThat(registry.find("c")).isEmpty();
    }

    @Test
    @DisplayName("重名注册仍报「已注册」，不被容量逻辑掩盖")
    void 重名注册仍报已注册() {
        ToolRegistry registry = new ToolRegistry(1);
        registry.register(tool("a"));
        assertThatThrownBy(() -> registry.register(tool("a")))
                .isInstanceOf(SwitchyardException.class)
                .hasMessageContaining("已注册");
    }
}
