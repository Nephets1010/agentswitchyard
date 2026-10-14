/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
/**
 * 命令行工具（picocli）：运维不打开浏览器也能跑流程、批审批、做回放、做体检。
 *
 * <p>CLI 与引擎之间只走 HTTP（{@code --base-url}，默认 http://localhost:8080）， CLI 本身零依赖引擎内部 —— 远程引擎与本地引擎行为一致。
 */
package io.github.nephets.agentswitchyard.starter.cli;
