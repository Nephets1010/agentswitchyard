/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.store;

import io.github.nephets.agentswitchyard.core.replay.ReplayBlob;
import io.github.nephets.agentswitchyard.core.replay.ReplayStore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 回放 blob 存储（core SPI {@link ReplayStore} 的 JDBC 实现，架构 §5.5 中的 TraceBlobStore）。
 *
 * <p>★ 确定性来自响应注入，不是模型快照（ADR-0004）：录制当时模型的<b>原始响应</b>， 回放时按请求摘要（sha256）命中注入，零上游调用。
 */
public final class TraceBlobStore implements ReplayStore {

    private final JdbcTemplate jdbc;

    /**
     * 创建回放 blob 存储。
     *
     * @param jdbc Spring {@link JdbcTemplate}
     */
    public TraceBlobStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(ReplayBlob blob) {
        // 同主键重复保存覆盖（录制重跑不产生脏数据）；先删后插，跨方言最可移植
        jdbc.update(
                "DELETE FROM replay_blob WHERE flow_instance_id = ? AND span_id = ? AND seq = ?",
                blob.instanceId(),
                blob.spanId(),
                blob.seq());
        jdbc.update(
                "INSERT INTO replay_blob (flow_instance_id, span_id, seq, model_id, request_digest,"
                        + " response_json, recorded_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                blob.instanceId(),
                blob.spanId(),
                blob.seq(),
                blob.modelId(),
                blob.requestDigest(),
                blob.responseJson(),
                Timestamp.from(blob.recordedAt()));
    }

    @Override
    public Optional<ReplayBlob> findByDigest(String instanceId, String requestDigest) {
        List<ReplayBlob> rows =
                jdbc.query(
                        BLOB_SELECT
                                + " WHERE flow_instance_id = ? AND request_digest = ?"
                                + " ORDER BY span_id, seq",
                        BLOB_MAPPER,
                        instanceId,
                        requestDigest);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<ReplayBlob> listBlobs(String instanceId) {
        return jdbc.query(
                BLOB_SELECT + " WHERE flow_instance_id = ? ORDER BY span_id, seq",
                BLOB_MAPPER,
                instanceId);
    }

    private static final String BLOB_SELECT =
            "SELECT flow_instance_id, span_id, seq, model_id, request_digest, response_json, recorded_at"
                    + " FROM replay_blob";

    private static final RowMapper<ReplayBlob> BLOB_MAPPER =
            (rs, rowNum) ->
                    new ReplayBlob(
                            rs.getString("flow_instance_id"),
                            rs.getString("span_id"),
                            rs.getInt("seq"),
                            rs.getString("model_id"),
                            rs.getString("request_digest"),
                            rs.getString("response_json"),
                            rs.getTimestamp("recorded_at").toInstant());
}
