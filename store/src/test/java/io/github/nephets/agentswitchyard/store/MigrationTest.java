/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.store;

import io.github.nephets.agentswitchyard.store.support.StoreContractProbe;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Flyway 迁移（H2）：V1 基线 → V2 唯一索引 → V3 审计列，可重复执行（幂等）。 */
@DisplayName("FlywayMigrator（H2 迁移链 V1→V2→V3）")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigrationTest {

    private JdbcTemplate jdbc;

    @BeforeAll
    void setUp() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:swy-migration;DB_CLOSE_DELAY=-1");
        int executed = FlywayMigrator.migrate(ds);
        assertThat(executed).isEqualTo(3);
        jdbc = new JdbcTemplate(ds);
    }

    @Test
    @DisplayName("重复迁移：0 个新版本（幂等）")
    void migrateIsIdempotent() {
        assertThat(FlywayMigrator.migrate(dataSourceOf(jdbc))).isZero();
    }

    @Test
    @DisplayName("V1 基线四表齐备，V3 审计列存在")
    void tablesAndAuditColumnsExist() throws Exception {
        try (Connection connection = DataSourceUtils.getConnection(dataSourceOf(jdbc));
                Statement statement = connection.createStatement()) {
            for (String table :
                    List.of("flow_instance", "step_record", "approval_record", "replay_blob")) {
                try (ResultSet rs =
                        statement.executeQuery(
                                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = '"
                                        + table.toUpperCase()
                                        + "'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).as("表 %s 应存在", table).isEqualTo(1);
                }
            }
            // V3 审计列
            try (ResultSet rs =
                    statement.executeQuery(
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'STEP_RECORD'"
                                    + " AND column_name = 'CREATED_BY'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).as("V3 审计列 created_by 应存在").isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("V2 唯一索引存在（幂等键同表唯一）")
    void uniqueIdemIndexExists() throws Exception {
        try (Connection connection = DataSourceUtils.getConnection(dataSourceOf(jdbc));
                Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery(
                                "SELECT COUNT(*) FROM information_schema.indexes WHERE table_name = 'STEP_RECORD'"
                                        + " AND index_name = 'UX_STEP_RECORD_IDEM'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).as("唯一索引 ux_step_record_idem 应存在").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("探针冒烟：迁移后的库可承载完整存储契约（轻量前半段）")
    void migratedSchemaServesContract() throws Exception {
        // 与 IT 的完整契约场景共用了探针的记录工厂；这里只验证 H2 上迁移后的 schema 可写可读
        StoreContractProbe.verifyAll(jdbc, Dialect.H2);
    }

    private static DataSource dataSourceOf(JdbcTemplate template) {
        return template.getDataSource();
    }
}
