/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.chat;

import io.github.nephets.agentswitchyard.core.usage.Usage;

/**
 * 模型回复（文本 + 归一化计量）。
 *
 * @param text 回复文本
 * @param usage Token 计量（USG-001）
 */
public record ChatReply(String text, Usage usage) {

    /** 紧凑构造器：null 归一。 */
    public ChatReply {
        if (usage == null) {
            usage =
                    Usage.estimated(
                            io.github.nephets.agentswitchyard.core.usage.UsageSource.FALLBACK,
                            false);
        }
    }
}
