/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.sample.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;

/**
 * 副作用台账（demo 的「真实世界」）：放款这一步的<b>唯一</b>副作用落点。
 *
 * <p>★ 幂等验收的观察窗口：CrashResumeIT 断言重启后台账里 {@code (instance_id, step_id)} 每个副作用<b>只有一行</b> —— 不多不少。
 */
@Component
public class Ledger {

    private final JdbcTemplate jdbc;

    /** 创建台账。 */
    public Ledger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 建表（幂等；demo 不上 Flyway —— schema 属于业务方，可靠性层只管 step_record）。 */
    @PostConstruct
    void initSchema() {
        jdbc.update(
                "CREATE TABLE IF NOT EXISTS demo_ledger ("
                        + "instance_id VARCHAR(64) NOT NULL,"
                        + "step_id VARCHAR(128) NOT NULL,"
                        + "detail VARCHAR(512) NOT NULL,"
                        + "cancelled BOOLEAN NOT NULL DEFAULT FALSE,"
                        + "created_at TIMESTAMP NOT NULL,"
                        + "CONSTRAINT pk_demo_ledger PRIMARY KEY (instance_id, step_id))");
    }

    /** 记录副作用（主键冲突 = 重复副作用 → 抛错让可靠性层感知）。 */
    public void recordSideEffect(String instanceId, String stepId, String detail) {
        jdbc.update(
                "INSERT INTO demo_ledger (instance_id, step_id, detail, cancelled, created_at)"
                        + " VALUES (?, ?, ?, FALSE, ?)",
                instanceId,
                stepId,
                detail,
                Timestamp.from(Instant.now()));
    }

    /** 查实例的全部副作用行（键统一小写 snake_case —— H2 会把列标签转成大写，需归一）。 */
    public List<Map<String, Object>> rows(String instanceId) {
        List<Map<String, Object>> raw =
                jdbc.queryForList(
                        "SELECT instance_id, step_id, detail, cancelled, created_at FROM demo_ledger"
                                + " WHERE instance_id = ? ORDER BY created_at",
                        instanceId);
        List<Map<String, Object>> normalized = new java.util.ArrayList<>(raw.size());
        for (Map<String, Object> row : raw) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            row.forEach((key, value) -> out.put(key.toLowerCase(), value));
            normalized.add(out);
        }
        return normalized;
    }

    /** 标记撤销（cancelReserve 补偿动作的落点）。 */
    public boolean markCancelled(String instanceId, String stepId) {
        return jdbc.update(
                        "UPDATE demo_ledger SET cancelled = TRUE WHERE instance_id = ? AND step_id = ?",
                        instanceId,
                        stepId)
                == 1;
    }
}
