/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.store.support;

import io.github.nephets.agentswitchyard.store.Dialect;

import java.util.List;

/**
 * 幂等插入语句（★ {@code tryBeginStep} 的「插入即判定」在 JDBC 层的载体）。
 *
 * <p>构造后不可变：{@link #sql()} 是方言化后的 SQL，{@link #leadingParams()} 说明 达梦 MERGE 形态下位于 VALUES
 * 占位符<b>之前</b>的 USING 子句参数个数（其余方言恒为 0）。
 */
public final class IdempotentInsert {

    private final String sql;
    private final int leadingParams;

    private IdempotentInsert(String sql, int leadingParams) {
        this.sql = sql;
        this.leadingParams = leadingParams;
    }

    /**
     * 构造幂等插入语句。
     *
     * @param dialect 方言
     * @param table 表名
     * @param columns 插入列（有序）
     * @param conflictColumns 冲突判定列（唯一键；必须按 {@code columns} 中的出现顺序给出，以便参数复用）
     * @return 幂等插入语句
     */
    public static IdempotentInsert of(
            Dialect dialect, String table, List<String> columns, List<String> conflictColumns) {
        // MERGE USING 形态（H2 / 达梦）的 USING 子句里也有冲突键参数，位于 VALUES 参数之前
        int leading =
                (dialect == Dialect.DAMENG || dialect == Dialect.H2) ? conflictColumns.size() : 0;
        return new IdempotentInsert(
                dialect.idempotentInsertSql(table, columns, conflictColumns), leading);
    }

    /** 方言化 SQL。 */
    public String sql() {
        return sql;
    }

    /**
     * VALUES 占位符之前的参数个数。
     *
     * <p>仅达梦 MERGE 形态非 0：绑定参数时先绑冲突键值（与冲突列顺序一致），再按 {@code columns} 顺序绑插入值。
     */
    public int leadingParams() {
        return leadingParams;
    }
}
