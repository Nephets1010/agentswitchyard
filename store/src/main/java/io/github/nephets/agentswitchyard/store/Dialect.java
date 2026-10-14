/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.store;

import java.util.Locale;

/**
 * SQL 方言（★ 四方言：H2 / MySQL / PostgreSQL / 达梦）。
 *
 * <p>四种方言对「幂等插入」（同键冲突时不插入任何行）的写法各不相同， 本枚举是唯一收敛点 —— {@code tryBeginStep} 的「插入即判定」语义依赖它。
 */
public enum Dialect {

    /** MySQL（含兼容协议的国产库网关）。幂等插入用 {@code INSERT IGNORE}。 */
    MYSQL,

    /** PostgreSQL。幂等插入用 {@code ON CONFLICT (...) DO NOTHING}。 */
    POSTGRESQL,

    /** H2（测试与单机 demo）。不支持 PostgreSQL 的 ON CONFLICT，用 H2 专有 {@code MERGE ... KEY}。 */
    H2,

    /** 达梦（DM8）。幂等插入用 {@code MERGE INTO ... WHEN NOT MATCHED}（达梦不支持 ON CONFLICT）。 */
    DAMENG;

    /**
     * 从 JDBC URL 推断方言。
     *
     * @param jdbcUrl JDBC 连接串
     * @return 方言；无法识别时抛 {@link IllegalArgumentException}（不静默降级 —— 错配方言会导致幂等语义失效）
     */
    public static Dialect fromUrl(String jdbcUrl) {
        String url = jdbcUrl == null ? "" : jdbcUrl.toLowerCase(Locale.ROOT);
        if (url.startsWith("jdbc:h2:")) {
            return H2;
        }
        if (url.startsWith("jdbc:mysql:")) {
            return MYSQL;
        }
        if (url.startsWith("jdbc:postgresql:")) {
            return POSTGRESQL;
        }
        if (url.startsWith("jdbc:dm:")) {
            return DAMENG;
        }
        throw new IllegalArgumentException(
                "无法识别的 JDBC URL（支持的方言：h2/mysql/postgresql/dm）: " + jdbcUrl);
    }

    /**
     * 生成「幂等插入」SQL：同冲突键已存在时不插入任何行（受影响行数为 0）。
     *
     * <p>达梦的 MERGE 形态下，冲突键的值出现在 {@code USING (SELECT ? AS ... FROM dual)} 子句里，
     * 绑定参数时<b>冲突键参数在前、插入列参数在后</b>（见 {@code IdempotentInsert.leadingParams()}）。
     *
     * @param table 表名
     * @param columns 插入列（有序）
     * @param conflictColumns 冲突判定列（唯一键；必须是 {@code columns} 的子集）
     * @return 可直接执行的 SQL
     */
    public String idempotentInsertSql(
            String table, java.util.List<String> columns, java.util.List<String> conflictColumns) {
        String cols = String.join(", ", columns);
        String placeholders = String.join(", ", java.util.Collections.nCopies(columns.size(), "?"));
        String conflict = String.join(", ", conflictColumns);
        return switch (this) {
            case MYSQL ->
                    "INSERT IGNORE INTO " + table + " (" + cols + ") VALUES (" + placeholders + ")";
            case POSTGRESQL ->
                    "INSERT INTO "
                            + table
                            + " ("
                            + cols
                            + ") VALUES ("
                            + placeholders
                            + ") ON CONFLICT ("
                            + conflict
                            + ") DO NOTHING";
            // H2 2.x 不支持 ON CONFLICT，专有 MERGE...KEY 又是「覆盖式 upsert」（会破坏 insert-wins 语义），
            // 因此走标准 MERGE USING；H2 推断不了裸 ? 的类型，需显式 CAST（冲突键按 schema 均为 VARCHAR）
            case H2 -> {
                String usingCols =
                        conflictColumns.stream()
                                .map(c -> "CAST(? AS VARCHAR(256)) AS " + c)
                                .reduce((a, b) -> a + ", " + b)
                                .orElse("");
                String onClause =
                        conflictColumns.stream()
                                .map(c -> "t." + c + " = s." + c)
                                .reduce((a, b) -> a + " AND " + b)
                                .orElse("");
                yield "MERGE INTO "
                        + table
                        + " t USING (SELECT "
                        + usingCols
                        + ") s ON ("
                        + onClause
                        + ") WHEN NOT MATCHED THEN INSERT ("
                        + cols
                        + ") VALUES ("
                        + placeholders
                        + ")";
            }
            case DAMENG -> {
                String usingCols =
                        conflictColumns.stream()
                                .map(c -> "? AS " + c)
                                .reduce((a, b) -> a + ", " + b)
                                .orElse("");
                String onClause =
                        conflictColumns.stream()
                                .map(c -> "t." + c + " = s." + c)
                                .reduce((a, b) -> a + " AND " + b)
                                .orElse("");
                yield "MERGE INTO "
                        + table
                        + " t USING (SELECT "
                        + usingCols
                        + " FROM dual) s ON ("
                        + onClause
                        + ") WHEN NOT MATCHED THEN INSERT ("
                        + cols
                        + ") VALUES ("
                        + placeholders
                        + ")";
            }
        };
    }

    /** 分页 LIMIT 子句（四方言恰好一致，显式化以便阅读）。 */
    public String limitClause(int limit) {
        return " LIMIT " + limit;
    }
}
