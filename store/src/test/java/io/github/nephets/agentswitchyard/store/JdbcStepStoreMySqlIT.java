/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.store;

import io.github.nephets.agentswitchyard.store.support.StoreContractProbe;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * JdbcStepStore / JdbcApprovalStore 完整契约 —— 真实 MySQL 8.4（Testcontainers）。
 *
 * <p>覆盖：方言化幂等插入（INSERT IGNORE）、并发同幂等键单副作用、状态机 CAS、 崩溃恢复前置、审批 CAS。场景与 PostgreSQL IT 共用 {@link
 * StoreContractProbe}。
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcStepStoreMySqlIT {

    @Container private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Test
    void fullContractOnMySql() throws Exception {
        SimpleDriverDataSource ds =
                new SimpleDriverDataSource(
                        new com.mysql.cj.jdbc.Driver(),
                        MYSQL.getJdbcUrl(),
                        MYSQL.getUsername(),
                        MYSQL.getPassword());
        FlywayMigrator.migrate(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        StoreContractProbe.verifyAll(jdbc, Dialect.MYSQL);
    }
}
