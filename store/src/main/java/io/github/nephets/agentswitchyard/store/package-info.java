/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
/**
 * 持久化层：StepStore / ApprovalStore / ReplayStore 的 JDBC 实现。
 *
 * <p>模块分层（架构 §5.5）：core 定义 SPI 与纯内存实现（可单测），本模块给出生产级 JDBC 实现， 支持 H2 / MySQL / PostgreSQL /
 * 达梦四种方言；幂等插入的方言差异收敛在 {@link io.github.nephets.agentswitchyard.store.Dialect}。
 */
package io.github.nephets.agentswitchyard.store;
