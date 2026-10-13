/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.executor;

import io.github.nephets.agentswitchyard.core.ErrorCode;
import io.github.nephets.agentswitchyard.core.Json;
import io.github.nephets.agentswitchyard.core.SwitchyardException;
import io.github.nephets.agentswitchyard.core.approval.ApprovalGate;
import io.github.nephets.agentswitchyard.core.approval.InMemoryApprovalStore;
import io.github.nephets.agentswitchyard.core.approval.RoleApprovalAuthorizer;
import io.github.nephets.agentswitchyard.core.chat.ChatGateway;
import io.github.nephets.agentswitchyard.core.chat.ChatReply;
import io.github.nephets.agentswitchyard.core.compensation.CompensationChain;
import io.github.nephets.agentswitchyard.core.definition.FlowDefinition;
import io.github.nephets.agentswitchyard.core.definition.StepHandler;
import io.github.nephets.agentswitchyard.core.instance.FlowState;
import io.github.nephets.agentswitchyard.core.instance.StepContext;
import io.github.nephets.agentswitchyard.core.instance.StepResult;
import io.github.nephets.agentswitchyard.core.replay.InMemoryReplayStore;
import io.github.nephets.agentswitchyard.core.replay.ReplayRecorder;
import io.github.nephets.agentswitchyard.core.retry.DefaultErrorClassifier;
import io.github.nephets.agentswitchyard.core.store.InMemoryStepStore;
import io.github.nephets.agentswitchyard.core.trace.W3CTraceCodec;
import io.github.nephets.agentswitchyard.core.usage.Usage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 确定性回放（core 层端到端）：录制网关跑完真实流程 → replay → identical=true、零真实调用； 无录制的实例 replay 直接抛
 * SW_BAD_REQUEST（ADR-0004）。
 */
@DisplayName("FlowExecutor.replay（录制 + 注入）")
class FlowExecutorReplayTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-10-10T08:00:00Z"), ZoneOffset.UTC);

    /** 轮询等待（与 FlowExecutorTest 相同的辅助）。 */
    private static void await(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("10s 内未等到期望条件");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        }
    }

    /** 录制网关：生产 SpringAiChatGateway 的 core 内等价物（同样按 spanId+seq 录制）。 */
    private static ChatGateway recordingGateway(
            ReplayRecorder recorder, Map<String, AtomicInteger> seqBySpan) {
        return (StepContext context, String modelId, String prompt) -> {
            String text = "REPLY[" + prompt.length() + "]";
            int seq =
                    seqBySpan
                            .computeIfAbsent(context.spanId(), k -> new AtomicInteger())
                            .getAndIncrement();
            // ★ 载荷形状与 ReplayChatModel.RecordedResponse 严格对齐（扁平三字段）
            String responseJson =
                    Json.write(Map.of("text", text, "inputTokens", 5, "outputTokens", 7));
            recorder.record(
                    context.flowInstanceId(),
                    context.spanId(),
                    seq,
                    modelId,
                    W3CTraceCodec.digest(prompt),
                    responseJson);
            return new ChatReply(text, Usage.vendorReported(5, 7));
        };
    }

    private static FlowDefinition llmFlow() {
        return FlowDefinition.builder("replay-flow")
                .llmStep("summarize")
                .description("LLM 摘要")
                .done()
                .ruleStep("decide")
                .description("规则决策")
                .done()
                .build();
    }

    @Test
    @DisplayName("真实执行后回放：identical=true（响应注入重跑，零真实调用）")
    void replayIsIdentical() {
        ReplayRecorder recorder = new ReplayRecorder(new InMemoryReplayStore());
        ChatGateway gateway = recordingGateway(recorder, new ConcurrentHashMap<>());
        FlowExecutor executor = newExecutor(gateway, recorder);

        String instanceId = executor.start("replay-flow", Map.of("ref", "R-1"), null);
        await(() -> executor.instance(instanceId).state() == FlowState.SUCCEEDED);
        assertThat(recorder.listBlobs(instanceId)).as("LLM 步骤的调用应有录制").hasSize(1);

        io.github.nephets.agentswitchyard.core.replay.TraceDiff diff = executor.replay(instanceId);
        assertThat(diff.identical()).as("回放差异：" + diff.diffs()).isTrue();
    }

    @Test
    @DisplayName("无录制的实例 replay → SW_BAD_REQUEST（不静默编造，ADR-0004）")
    void replayWithoutRecordingsThrows() {
        // 网关可用但不录制（recorder=null）→ 流程能成功，但回放无素材
        ChatGateway noRecorder =
                (StepContext context, String modelId, String prompt) ->
                        new ChatReply("REPLY", Usage.vendorReported(5, 7));
        FlowExecutor executor = newExecutor(noRecorder, null);
        String instanceId = executor.start("replay-flow", Map.of("ref", "R-2"), null);
        await(() -> executor.instance(instanceId).state() == FlowState.SUCCEEDED);

        assertThatThrownBy(() -> executor.replay(instanceId))
                .isInstanceOf(SwitchyardException.class)
                .hasMessageContaining("没有可回放的模型录制")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SW_BAD_REQUEST);
    }

    @Test
    @DisplayName("回放中审批步骤不挂起：审批流也能原样重跑")
    void replayOfApprovalFlowDoesNotSuspend() {
        ReplayRecorder recorder = new ReplayRecorder(new InMemoryReplayStore());
        ChatGateway gateway = recordingGateway(recorder, new ConcurrentHashMap<>());
        FlowDefinition flow =
                FlowDefinition.builder("approval-replay")
                        .llmStep("ai-check")
                        .description("AI 检查")
                        .done()
                        .humanStep("manual-review")
                        .approvalRequired("risk-officer")
                        .description("人工复核")
                        .done()
                        .build();
        ApprovalGate gate =
                new ApprovalGate(
                        new InMemoryApprovalStore(), new RoleApprovalAuthorizer(), null, FIXED);
        InMemoryStepStore store = new InMemoryStepStore();
        FlowExecutor executor =
                new FlowExecutor(
                        store,
                        Map.of(
                                "ai-check",
                                (StepHandler)
                                        ctx -> {
                                            var reply =
                                                    ctx.chat(
                                                            "demo-gpt",
                                                            ctx.input("ref").toString());
                                            return new StepResult(
                                                    Map.of("text", reply.text()),
                                                    reply.usage(),
                                                    null);
                                        },
                                "manual-review",
                                ctx -> StepResult.ofRule(Map.of("reviewed", true))),
                        gate,
                        new CompensationChain(Map.of(), FIXED, step -> null),
                        gateway,
                        new DefaultErrorClassifier(),
                        FIXED,
                        recorder);
        executor.register(flow);

        String instanceId = executor.start("approval-replay", Map.of("ref", "R-3"), null);
        await(() -> executor.instance(instanceId).state() == FlowState.WAITING_APPROVAL);
        gate.decide(
                gate.listPending("risk-officer").get(0).approvalId(),
                new io.github.nephets.agentswitchyard.core.approval.ApprovalDecision(
                        "zhang-san", "risk-officer", true, "同意", false, null));
        await(() -> executor.instance(instanceId).state() == FlowState.SUCCEEDED);

        io.github.nephets.agentswitchyard.core.replay.TraceDiff diff = executor.replay(instanceId);
        assertThat(diff.identical()).as("审批流回放差异：" + diff.diffs()).isTrue();
        // 真实账本未被动过（回放跑在隔离存储）
        assertThat(executor.instance(instanceId).state()).isEqualTo(FlowState.SUCCEEDED);
    }

    private static FlowExecutor newExecutor(ChatGateway gateway, ReplayRecorder recorder) {
        ApprovalGate gate =
                new ApprovalGate(
                        new InMemoryApprovalStore(), new RoleApprovalAuthorizer(), null, FIXED);
        FlowExecutor executor =
                new FlowExecutor(
                        new InMemoryStepStore(),
                        Map.of(
                                "summarize",
                                (StepHandler)
                                        ctx -> {
                                            io.github.nephets.agentswitchyard.core.chat.ChatReply
                                                    reply =
                                                            ctx.chat(
                                                                    "demo-gpt",
                                                                    "summarize:"
                                                                            + ctx.input("ref"));
                                            return new StepResult(
                                                    Map.of("text", reply.text()),
                                                    reply.usage(),
                                                    null);
                                        },
                                "decide",
                                ctx -> StepResult.ofRule(Map.of("decision", "PASS"))),
                        gate,
                        new CompensationChain(Map.of(), FIXED, step -> null),
                        gateway,
                        new DefaultErrorClassifier(),
                        FIXED,
                        recorder);
        executor.register(llmFlow());
        return executor;
    }
}
