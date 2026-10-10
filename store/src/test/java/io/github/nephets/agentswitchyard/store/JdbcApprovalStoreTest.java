/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.store;

import io.github.nephets.agentswitchyard.core.approval.ApprovalRecord;
import io.github.nephets.agentswitchyard.core.approval.ApprovalState;
import io.github.nephets.agentswitchyard.core.approval.ApprovalTimeoutPolicy;
import io.github.nephets.agentswitchyard.store.support.StoreContractProbe;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** JdbcApprovalStore（H2）：save 幂等、CAS 决策、待办列表、留痕读取。 */
@DisplayName("JdbcApprovalStore（H2）")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcApprovalStoreTest {

    private JdbcApprovalStore store;

    @BeforeAll
    void setUp() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:swy-approval;DB_CLOSE_DELAY=-1");
        FlywayMigrator.migrate(ds);
        store = new JdbcApprovalStore(new JdbcTemplate(ds), Dialect.H2);
    }

    @Test
    @DisplayName("★ 并发决策 20 线程：条件 UPDATE 保证只有第一个生效（硬验收）")
    void concurrentDecideOnlyFirstWins() throws Exception {
        store.save(StoreContractProbe.approval("appr-h2-cas", "inst-h2-cas"));
        AtomicInteger winners = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Future<Boolean>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            final boolean approve = i % 2 == 0;
            futures.add(
                    pool.submit(
                            () ->
                                    store.compareAndDecide(
                                            "appr-h2-cas",
                                            ApprovalState.PENDING,
                                            approve
                                                    ? ApprovalState.APPROVED
                                                    : ApprovalState.REJECTED,
                                            "approver-" + Thread.currentThread().threadId(),
                                            approve ? "同意" : "拒绝",
                                            false,
                                            Instant.now())));
        }
        for (Future<Boolean> future : futures) {
            if (future.get()) {
                winners.incrementAndGet();
            }
        }
        pool.shutdown();

        assertThat(winners.get()).as("并发决策只有 1 个生效").isEqualTo(1);
        ApprovalRecord after = store.find("appr-h2-cas").orElseThrow();
        assertThat(after.state()).isIn(ApprovalState.APPROVED, ApprovalState.REJECTED);
        assertThat(after.decidedAt()).isNotNull();
        // 决策后再 CAS：返回 false（已被决策）
        assertThat(
                        store.compareAndDecide(
                                "appr-h2-cas",
                                ApprovalState.PENDING,
                                ApprovalState.APPROVED,
                                "late-comer",
                                "迟到",
                                false,
                                Instant.now()))
                .isFalse();
    }

    @Test
    @DisplayName("save 幂等：同 ID 重复保存保留首行")
    void saveIsIdempotent() {
        store.save(StoreContractProbe.approval("appr-h2-idem", "inst-h2-idem"));
        store.save(StoreContractProbe.approval("appr-h2-idem", "inst-h2-idem"));
        assertThat(store.find("appr-h2-idem")).isPresent();
        assertThat(
                        store.listAllPending().stream()
                                .filter(r -> r.approvalId().equals("appr-h2-idem")))
                .hasSize(1);
    }

    @Test
    @DisplayName("listPending 按角色过滤；listByInstance 全量留痕")
    void listPendingFiltersByRole() {
        store.save(StoreContractProbe.approval("appr-h2-p1", "inst-h2-list"));
        store.save(
                new ApprovalRecord(
                        "appr-h2-p2",
                        "inst-h2-list",
                        "sign-off",
                        "manager",
                        ApprovalState.PENDING,
                        null,
                        null,
                        "主管签发",
                        false,
                        Instant.parse("2026-10-10T08:00:00Z"),
                        null,
                        null,
                        ApprovalTimeoutPolicy.AUTO_REJECT));
        assertThat(store.listPending("risk-officer").stream().map(ApprovalRecord::approvalId))
                .contains("appr-h2-p1");
        assertThat(store.listPending("manager").stream().map(ApprovalRecord::approvalId))
                .contains("appr-h2-p2");
        assertThat(store.listByInstance("inst-h2-list")).hasSize(2);
        assertThat(store.find("appr-h2-p1").orElseThrow().timeoutPolicy())
                .isEqualTo(ApprovalTimeoutPolicy.HOLD_FOREVER);
    }
}
