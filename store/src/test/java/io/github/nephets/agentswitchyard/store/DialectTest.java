/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 四方言幂等插入 SQL 生成（★ 达梦无社区版 Flyway/Testcontainers 支撑， 首版对达梦做 SQL 生成层验证 —— 诚实降级项，见 README 支持矩阵）。 */
@DisplayName("Dialect 四方言 SQL 生成")
class DialectTest {

    private static final List<String> COLUMNS =
            List.of("flow_instance_id", "step_id", "seq", "kind", "idem_key", "state");
    private static final List<String> CONFLICT = List.of("flow_instance_id", "step_id", "idem_key");

    @Test
    @DisplayName("MySQL → INSERT IGNORE")
    void mysqlUsesInsertIgnore() {
        String sql = Dialect.MYSQL.idempotentInsertSql("step_record", COLUMNS, CONFLICT);
        assertThat(sql)
                .startsWith("INSERT IGNORE INTO step_record")
                .contains("VALUES (?, ?, ?, ?, ?, ?)");
    }

    @Test
    @DisplayName("PostgreSQL → ON CONFLICT DO NOTHING")
    void postgresUsesOnConflict() {
        String sql = Dialect.POSTGRESQL.idempotentInsertSql("step_record", COLUMNS, CONFLICT);
        assertThat(sql)
                .startsWith("INSERT INTO step_record")
                .endsWith("ON CONFLICT (flow_instance_id, step_id, idem_key) DO NOTHING");
    }

    @Test
    @DisplayName("H2 → 标准 MERGE USING（H2 2.x 不支持 ON CONFLICT；MERGE...KEY 是覆盖式 upsert 不可用）")
    void h2UsesMergeUsing() {
        String sql = Dialect.H2.idempotentInsertSql("step_record", COLUMNS, CONFLICT);
        assertThat(sql)
                .startsWith(
                        "MERGE INTO step_record t USING (SELECT CAST(? AS VARCHAR(256)) AS flow_instance_id")
                .contains(
                        "ON (t.flow_instance_id = s.flow_instance_id AND t.step_id = s.step_id"
                                + " AND t.idem_key = s.idem_key)")
                .contains("WHEN NOT MATCHED THEN INSERT")
                .contains("VALUES (?, ?, ?, ?, ?, ?)");
    }

    @Test
    @DisplayName("达梦 → MERGE INTO ... WHEN NOT MATCHED")
    void damengUsesMerge() {
        String sql = Dialect.DAMENG.idempotentInsertSql("step_record", COLUMNS, CONFLICT);
        assertThat(sql)
                .startsWith("MERGE INTO step_record t")
                .contains(
                        "USING (SELECT ? AS flow_instance_id, ? AS step_id, ? AS idem_key FROM dual)")
                .contains(
                        "ON (t.flow_instance_id = s.flow_instance_id AND t.step_id = s.step_id"
                                + " AND t.idem_key = s.idem_key)")
                .contains("WHEN NOT MATCHED THEN INSERT")
                .contains("VALUES (?, ?, ?, ?, ?, ?)");
    }

    @Test
    @DisplayName("fromUrl：四种 URL 各归其位，未知 URL fail-fast")
    void fromUrl() {
        assertThat(Dialect.fromUrl("jdbc:h2:mem:test")).isEqualTo(Dialect.H2);
        assertThat(Dialect.fromUrl("jdbc:mysql://localhost:3306/swy")).isEqualTo(Dialect.MYSQL);
        assertThat(Dialect.fromUrl("jdbc:postgresql://localhost:5432/swy"))
                .isEqualTo(Dialect.POSTGRESQL);
        assertThat(Dialect.fromUrl("jdbc:dm://localhost:5236")).isEqualTo(Dialect.DAMENG);
        assertThatThrownBy(() -> Dialect.fromUrl("jdbc:oracle:thin:@localhost:1521:xe"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法识别的 JDBC URL");
    }
}
