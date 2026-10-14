/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.store;

import io.github.nephets.agentswitchyard.core.instance.FlowState;
import io.github.nephets.agentswitchyard.core.instance.StepState;
import io.github.nephets.agentswitchyard.core.store.StepStore;
import io.github.nephets.agentswitchyard.store.support.StoreContractProbe;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JdbcStepStore 完整契约（H2）：tryBeginStep 插入即判定、并发幂等、状态机 CAS、崩溃恢复。 MySQL / PostgreSQL 的同一契约场景见
 * Testcontainers IT。
 */
@DisplayName("JdbcStepStore（H2 完整契约）")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcStepStoreTest {

    private JdbcTemplate jdbc;
    private StepStore store;

    @BeforeAll
    void setUp() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:swy-stepstore;DB_CLOSE_DELAY=-1");
        FlywayMigrator.migrate(ds);
        jdbc = new JdbcTemplate(ds);
        store = new JdbcStepStore(jdbc, Dialect.H2);
    }

    @Test
    @DisplayName("★ 并发同幂等键 20 线程：插入即判定，只有 1 个赢家、副作用只发生 1 次（PRD P0-2）")
    void concurrentSameIdemKeySingleSideEffect() throws Exception {
        store.createInstance(StoreContractProbe.instance("inst-h2-idem"));
        io.github.nephets.agentswitchyard.core.idempotency.IdempotencyGuard guard =
                new io.github.nephets.agentswitchyard.core.idempotency.IdempotencyGuard(store);
        java.util.concurrent.atomic.AtomicInteger sideEffects =
                new java.util.concurrent.atomic.AtomicInteger();
        int threads = 20;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        List<java.util.concurrent.Future<Boolean>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final io.github.nephets.agentswitchyard.core.instance.StepRecord candidate =
                    StoreContractProbe.stepRecord("inst-h2-idem", "s1", "H2-1:s1");
            futures.add(
                    pool.submit(
                            () -> {
                                // 赢家执行副作用并落库；输家复用首次结果
                                if (guard.begin("inst-h2-idem", candidate).isEmpty()) {
                                    sideEffects.incrementAndGet();
                                    store.completeStep(
                                            candidate.succeeded(
                                                    "{\"tx\":1}",
                                                    io.github.nephets.agentswitchyard.core.usage
                                                            .Usage.estimated(
                                                            io.github.nephets.agentswitchyard.core
                                                                    .usage.UsageSource.FALLBACK,
                                                            false),
                                                    java.math.BigDecimal.ZERO,
                                                    Instant.now()),
                                            Instant.now());
                                    return true;
                                }
                                return false;
                            }));
        }
        int winners = 0;
        for (java.util.concurrent.Future<Boolean> future : futures) {
            if (future.get()) {
                winners++;
            }
        }
        pool.shutdown();

        assertThat(winners).as("20 线程同幂等键只有 1 个赢家").isEqualTo(1);
        assertThat(sideEffects.get()).as("副作用只发生 1 次").isEqualTo(1);
        assertThat(store.findStep("inst-h2-idem", "s1").orElseThrow().outputJson())
                .isEqualTo("{\"tx\":1}");
    }

    @Test
    @DisplayName("tryBeginStep：第二次调用返回既有记录（非 candidate 引用）")
    void tryBeginStepReturnsExistingOnConflict() {
        store.createInstance(StoreContractProbe.instance("inst-h2-conflict"));
        var first = StoreContractProbe.stepRecord("inst-h2-conflict", "s1", "H2-C:s1");
        var second = StoreContractProbe.stepRecord("inst-h2-conflict", "s1", "H2-C:s1");
        assertThat(store.tryBeginStep(first)).isSameAs(first);
        var returned = store.tryBeginStep(second);
        assertThat(returned).isNotSameAs(second);
        assertThat(returned.stepId()).isEqualTo("s1");
        assertThat(returned.idemKey()).isEqualTo("H2-C:s1");
    }

    @Test
    @DisplayName("状态机 CAS：非法转移 fail-fast（PENDING → COMPENSATING 拒绝）")
    void illegalTransitionRejected() {
        store.createInstance(StoreContractProbe.instance("inst-h2-cas"));
        assertThatThrownBy(
                        () ->
                                store.compareAndSetInstanceState(
                                        "inst-h2-cas",
                                        FlowState.PENDING,
                                        FlowState.COMPENSATING,
                                        Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非法的流程状态转移");
    }

    @Test
    @DisplayName("updateInstanceState 同样校验状态机")
    void updateInstanceStateValidates() {
        store.createInstance(StoreContractProbe.instance("inst-h2-update"));
        assertThatThrownBy(
                        () ->
                                store.updateInstanceState(
                                        "inst-h2-update", FlowState.SUCCEEDED, Instant.now()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markStepResumed：WAITING_APPROVAL → RUNNING（审批通过续跑）")
    void markStepResumed() {
        store.createInstance(StoreContractProbe.instance("inst-h2-resume"));
        store.tryBeginStep(StoreContractProbe.stepRecord("inst-h2-resume", "s1", "H2-R:s1"));
        store.markWaitingApproval("inst-h2-resume", "s1", "appr-1", Instant.now());
        assertThat(store.findStep("inst-h2-resume", "s1").orElseThrow().state())
                .isEqualTo(StepState.WAITING_APPROVAL);
        store.markStepResumed("inst-h2-resume", "s1", Instant.now());
        assertThat(store.findStep("inst-h2-resume", "s1").orElseThrow().state())
                .isEqualTo(StepState.RUNNING);
    }

    @Test
    @DisplayName("markCompensated：成功/失败两种落点")
    void markCompensated() {
        store.createInstance(StoreContractProbe.instance("inst-h2-comp"));
        store.tryBeginStep(StoreContractProbe.stepRecord("inst-h2-comp", "s1", "H2-M:s1"));
        store.tryBeginStep(StoreContractProbe.stepRecord("inst-h2-comp", "s2", "H2-M:s2"));
        store.completeStep(store.findStep("inst-h2-comp", "s1").orElseThrow(), Instant.now());
        store.completeStep(store.findStep("inst-h2-comp", "s2").orElseThrow(), Instant.now());
        store.markCompensated("inst-h2-comp", "s1", true, Instant.now());
        store.markCompensated("inst-h2-comp", "s2", false, Instant.now());
        assertThat(store.findStep("inst-h2-comp", "s1").orElseThrow().state())
                .isEqualTo(StepState.COMPENSATED);
        assertThat(store.findStep("inst-h2-comp", "s2").orElseThrow().state())
                .isEqualTo(StepState.COMPENSATE_FAILED);
        assertThat(store.listCompletedSteps("inst-h2-comp")).isEmpty(); // 补偿后不再是 succeeded
    }

    @Test
    @DisplayName("loadInstance：不存在抛 IllegalArgumentException")
    void loadMissingInstanceThrows() {
        assertThatThrownBy(() -> store.loadInstance("no-such"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("流程实例不存在");
    }
}
