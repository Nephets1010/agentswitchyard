/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.usage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** USG-001 不变量：非负、cached ⊆ input、total 一律重算。 */
@DisplayName("Usage 契约不变量（USG-001）")
class UsageValidationTest {

    @Test
    @DisplayName("totalTokens 一律重算，不信任上游传入值")
    void totalIsRecomputed() {
        // 传入的 total=99999 会被紧凑构造器直接覆盖
        Usage usage = new Usage(10, 20, 5, 0, 99_999, UsageSource.VENDOR_REPORTED, false, false);
        assertThat(usage.totalTokens()).isEqualTo(35);
    }

    @Test
    @DisplayName("负值 token 计数直接拒绝（fail-fast 在开发期）")
    void negativeRejected() {
        assertThatThrownBy(() -> Usage.of(-1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("负值");
    }

    @Test
    @DisplayName("cachedInputTokens > inputTokens 拒绝：同一段 token 不允许重复计费")
    void cachedGreaterThanInputRejected() {
        assertThatThrownBy(
                        () ->
                                new Usage(
                                        10,
                                        20,
                                        0,
                                        11,
                                        0,
                                        UsageSource.VENDOR_REPORTED,
                                        false,
                                        false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cachedInputTokens");
    }

    @Test
    @DisplayName("usageSource 不可为 null")
    void nullSourceRejected() {
        assertThatThrownBy(() -> new Usage(1, 1, 0, 0, 0, null, false, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("estimated 工厂：全 0 且来源显式")
    void estimatedFactory() {
        Usage usage = Usage.estimated(UsageSource.FALLBACK, false);
        assertThat(usage.isZero()).isTrue();
        assertThat(usage.usageSource()).isEqualTo(UsageSource.FALLBACK);
        assertThat(usage.estimatedOnly()).isTrue();
        assertThat(usage.billableInputTokens()).isZero();
    }
}
