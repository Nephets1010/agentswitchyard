/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.store;

import org.flywaydb.core.Flyway;

import javax.sql.DataSource;

/**
 * Flyway 迁移执行器（V1 基线表 → V2 唯一索引 → V3 审计列）。
 *
 * <p>迁移脚本对 H2 / MySQL / PostgreSQL 使用同一套可移植 DDL；达梦经 SQL 生成层验证 （达梦无社区版 Flyway 模块，生产接入时用达梦官方 Flyway
 * 分支或手工执行同一 DDL）。
 */
public final class FlywayMigrator {

    private FlywayMigrator() {
        // 工具类不允许实例化
    }

    /**
     * 执行迁移（可重复调用：已应用的版本自动跳过）。
     *
     * @param dataSource 数据源
     * @return 本次实际执行的迁移数
     */
    public static int migrate(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate()
                .migrationsExecuted;
    }
}
