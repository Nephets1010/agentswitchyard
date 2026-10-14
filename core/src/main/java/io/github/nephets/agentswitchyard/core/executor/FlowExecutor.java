/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.executor;

import io.github.nephets.agentswitchyard.core.ErrorCode;
import io.github.nephets.agentswitchyard.core.Json;
import io.github.nephets.agentswitchyard.core.SwitchyardException;
import io.github.nephets.agentswitchyard.core.approval.ApprovalGate;
import io.github.nephets.agentswitchyard.core.approval.ApprovalRecord;
import io.github.nephets.agentswitchyard.core.approval.ApprovalRequest;
import io.github.nephets.agentswitchyard.core.approval.ApprovalTimeoutPolicy;
import io.github.nephets.agentswitchyard.core.chat.ChatGateway;
import io.github.nephets.agentswitchyard.core.chat.ChatReply;
import io.github.nephets.agentswitchyard.core.compensation.CompensationChain;
import io.github.nephets.agentswitchyard.core.compensation.CompensationReport;
import io.github.nephets.agentswitchyard.core.definition.FlowDefinition;
import io.github.nephets.agentswitchyard.core.definition.StepDefinition;
import io.github.nephets.agentswitchyard.core.definition.StepHandler;
import io.github.nephets.agentswitchyard.core.idempotency.IdempotencyGuard;
import io.github.nephets.agentswitchyard.core.instance.FlowInstance;
import io.github.nephets.agentswitchyard.core.instance.FlowState;
import io.github.nephets.agentswitchyard.core.instance.StepContext;
import io.github.nephets.agentswitchyard.core.instance.StepExecutor;
import io.github.nephets.agentswitchyard.core.instance.StepRecord;
import io.github.nephets.agentswitchyard.core.instance.StepResult;
import io.github.nephets.agentswitchyard.core.instance.StepState;
import io.github.nephets.agentswitchyard.core.replay.InMemoryReplayStore;
import io.github.nephets.agentswitchyard.core.replay.ReplayBlob;
import io.github.nephets.agentswitchyard.core.replay.ReplayChatModel;
import io.github.nephets.agentswitchyard.core.replay.ReplayComparator;
import io.github.nephets.agentswitchyard.core.replay.ReplayRecorder;
import io.github.nephets.agentswitchyard.core.replay.TraceDiff;
import io.github.nephets.agentswitchyard.core.retry.ErrorClassifier;
import io.github.nephets.agentswitchyard.core.store.InMemoryStepStore;
import io.github.nephets.agentswitchyard.core.store.StepStore;
import io.github.nephets.agentswitchyard.core.support.EvictionTracker;
import io.github.nephets.agentswitchyard.core.trace.TraceContext;
import io.github.nephets.agentswitchyard.core.usage.Usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流程执行引擎（有序步骤 + 断点续跑 + 审批挂起 + 反向补偿 + 确定性回放）。
 *
 * <p>★ 执行模型：每个流程实例跑在自己的虚拟线程上；步骤顺序执行；审批步骤把实例挂起 （WAITING_APPROVAL 持久化），决策回调后在新的虚拟线程上从挂起步续跑。
 * 进程崩溃后，{@code resume} / {@code recoverInterrupted} 从「第一个未成功步骤」续跑 —— 已成功步骤被幂等守卫直接跳过，副作用不重复。
 */
public final class FlowExecutor implements ApprovalGate.DecidedListener {

    private static final Logger LOG = LoggerFactory.getLogger(FlowExecutor.class);

    /** 补偿报告缓存默认容量上限（与 {@code InMemoryReplayStore} 的 10_000 对齐家族约定）。 */
    private static final int DEFAULT_MAX_COMPENSATION_REPORTS = 10_000;

    private final Map<String, FlowDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, StepHandler> handlers;
    private final StepStore store;
    private final IdempotencyGuard guard;
    private final StepExecutor stepExecutor;
    private final ApprovalGate approvalGate;
    private final CompensationChain compensationChain;
    private final ChatGateway chatGateway;
    private final ReplayRecorder replayRecorder;
    private final Clock clock;

    /**
     * 补偿报告缓存（有界 LRU）。
     *
     * <p>★ 为什么必须有界：key = flowInstanceId，进程生命周期内只增不减 —— 长时间运行的生产进程里 「失败并补偿的流程」持续累积，无界增长必然 OOM。
     *
     * <p>★ 为什么不是「补偿后即删」：本表是运维排查「失败流程到底补偿到哪一步」的聚合查询入口， 删了就等于把证据丢了（JDBC 存储里只有逐步骤的 markCompensated
     * 结果，没有聚合视图）。
     *
     * <p>★ 淘汰是降级而非故障：被淘汰后 {@link #compensationReport(String)} 返回 empty（数据本体 已随步骤状态落库），首次淘汰与后续每
     * 10_000 条经 {@link EvictionTracker} 打 warn —— 不留静默失败。
     */
    private final Map<String, CompensationReport> compensationReports;

    /** 淘汰记账（包级可见供回归测试断言「淘汰可见」）。 */
    private final EvictionTracker compensationEvictions =
            new EvictionTracker("compensationReports");

    /** 容量上限（volatile：回归测试可在运行前压缩，生产恒为默认值）。 */
    private volatile int compensationReportCapacity = DEFAULT_MAX_COMPENSATION_REPORTS;

    /**
     * 创建执行引擎。
     *
     * @param store 步骤存储
     * @param handlers 业务处理器（key = stepId）
     * @param approvalGate 审批门
     * @param compensationChain 补偿链
     * @param chatGateway LLM 网关（无 LLM 步骤可为 null）
     * @param errorClassifier 错误分类器
     * @param clock 时钟（可注入）
     * @param replayRecorder 回放录制器（无需回放能力可为 null）
     */
    public FlowExecutor(
            StepStore store,
            Map<String, StepHandler> handlers,
            ApprovalGate approvalGate,
            CompensationChain compensationChain,
            ChatGateway chatGateway,
            ErrorClassifier errorClassifier,
            Clock clock,
            ReplayRecorder replayRecorder) {
        this.store = store;
        this.handlers = handlers == null ? Map.of() : Map.copyOf(handlers);
        this.approvalGate = approvalGate;
        this.approvalGate.setListener(this);
        this.compensationChain = compensationChain;
        this.chatGateway = chatGateway;
        this.replayRecorder = replayRecorder;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.guard = new IdempotencyGuard(store);
        this.stepExecutor = new StepExecutor(errorClassifier);
        // 补偿报告：访问顺序 LRU。synchronizedMap 而非 ConcurrentHashMap ——
        // LinkedHashMap 的 removeEldestEntry 必须在持锁环境下判定，否则淘汰与并发写交错会丢条目
        LinkedHashMap<String, CompensationReport> reportBacking =
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(
                            Map.Entry<String, CompensationReport> eldest) {
                        if (size() > compensationReportCapacity) {
                            compensationEvictions.onEvict();
                            return true;
                        }
                        return false;
                    }
                };
        this.compensationReports = Collections.synchronizedMap(reportBacking);
    }

    /**
     * 包级可见：仅回归测试用于压缩容量上限、验证淘汰行为（生产代码不调用；容量恒为默认值）。
     *
     * @param capacity 压缩后的容量上限（必须 &gt; 0）
     */
    void setCompensationReportCapacityForTest(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity 必须 > 0: " + capacity);
        }
        this.compensationReportCapacity = capacity;
    }

    /** 包级可见：累计被淘汰的补偿报告数（0 = 从未淘汰；回归测试断言「淘汰可见」用）。 */
    long compensationReportEvictions() {
        return compensationEvictions.evictedCount();
    }

    /** 注册流程定义（同 flowId 重注册覆盖 —— 开发期热更；实例上记录的是启动时的版本号）。 */
    public void register(FlowDefinition definition) {
        definitions.put(definition.flowId(), definition);
    }

    /** 已注册的流程 ID 集合。 */
    public Set<String> registeredFlowIds() {
        return definitions.keySet();
    }

    /** 查询流程定义。 */
    public FlowDefinition definition(String flowId) {
        return definitions.get(flowId);
    }

    /**
     * 启动流程（异步：虚拟线程上执行，立即返回实例 ID）。
     *
     * @param flowId 流程 ID
     * @param input 流程输入
     * @param tenantId 租户（可 null）
     * @return 实例 ID
     */
    public String start(String flowId, Map<String, Object> input, String tenantId) {
        FlowDefinition definition = definitionOf(flowId);
        String instanceId = java.util.UUID.randomUUID().toString();
        FlowInstance instance =
                new FlowInstance(
                        instanceId,
                        flowId,
                        definition.version(),
                        tenantId,
                        TraceContext.current().traceId(),
                        FlowState.PENDING,
                        input,
                        clock.instant(),
                        clock.instant());
        store.createInstance(instance);
        LOG.info(
                "流程启动 instId={} flow={} v={} tenant={} traceId={}",
                instanceId,
                flowId,
                definition.version(),
                tenantId,
                instance.traceId());
        Thread.ofVirtual()
                .name("swy-flow-" + instanceId)
                .start(() -> runFlowSync(instanceId, false));
        return instanceId;
    }

    /**
     * 恢复单个实例（崩溃恢复入口）：从第一个未成功步骤续跑，已成功步骤被幂等守卫跳过。
     *
     * @param flowInstanceId 实例 ID
     */
    public void resume(String flowInstanceId) {
        FlowInstance instance = store.loadInstance(flowInstanceId);
        if (instance.state().terminal()) {
            return; // 终态无需恢复
        }
        if (instance.state() == FlowState.WAITING_APPROVAL) {
            return; // 挂起态保持等待审批回调，续跑由 decide 触发
        }
        // 崩溃残留的 RUNNING 步骤置为 FAILED：幂等守卫不再等待死去的赢家，允许重新执行
        store.failInterruptedSteps(flowInstanceId, clock.instant());
        Thread.ofVirtual()
                .name("swy-resume-" + flowInstanceId)
                .start(() -> runFlowSync(flowInstanceId, false));
    }

    /**
     * 恢复全部被中断（RUNNING 但进程已死）的实例 —— starter 启动时调用。
     *
     * <p>进程被 kill -9 后实例停在 RUNNING 状态；重启后本方法把它们全部续跑。
     *
     * @return 恢复的实例数
     */
    public int recoverInterrupted() {
        List<FlowInstance> interrupted = store.listInstancesInState(FlowState.RUNNING);
        interrupted.forEach(
                instance -> {
                    store.failInterruptedSteps(instance.flowInstanceId(), clock.instant());
                    Thread.ofVirtual()
                            .name("swy-resume-" + instance.flowInstanceId())
                            .start(() -> runFlowSync(instance.flowInstanceId(), false));
                });
        return interrupted.size();
    }

    /** 查询实例。 */
    public FlowInstance instance(String flowInstanceId) {
        return store.loadInstance(flowInstanceId);
    }

    /** 查询实例全部步骤（seq 升序）。 */
    public List<StepRecord> steps(String flowInstanceId) {
        return store.listSteps(flowInstanceId);
    }

    /**
     * 查询补偿报告（未触发补偿返回 empty）。
     *
     * <p>★ 已触发补偿但报告被 LRU 淘汰时同样返回 empty（容量默认 {@value #DEFAULT_MAX_COMPENSATION_REPORTS}，淘汰有 warn
     * 告警）—— 此时请改看步骤状态 （{@link #steps(String)} 的 COMPENSATED / COMPENSATE_FAILED）。
     */
    public Optional<CompensationReport> compensationReport(String flowInstanceId) {
        return Optional.ofNullable(compensationReports.get(flowInstanceId));
    }

    /** 导出执行轨迹。 */
    public io.github.nephets.agentswitchyard.core.trace.ExecutionTrace trace(
            String flowInstanceId) {
        FlowInstance instance = store.loadInstance(flowInstanceId);
        FlowDefinition definition = definitions.get(instance.flowId());
        return new io.github.nephets.agentswitchyard.core.trace.ExecutionTrace(
                instance,
                instance.flowId(),
                definition == null ? "1" : String.valueOf(definition.version()),
                store.listSteps(flowInstanceId));
    }

    /**
     * 确定性回放：用录制响应在隔离的内存存储里重跑，返回与原始执行的差异。
     *
     * <p>回放<b>不产生真实副作用</b>：跑在 {@link InMemoryStepStore} 上，handler 收到 {@code replay=true}
     * 属性后应只重算输出、跳过外部副作用；LLM 响应由 {@link ReplayChatModel} 按请求摘要命中注入，<b>零上游调用</b>。
     * 审批步骤在回放中不挂起（以历史决策结果直接执行，handler 自行输出历史结果）。
     *
     * @param flowInstanceId 原始实例 ID
     * @return 差异报告（{@code identical=true} 表示与历史完全一致）
     * @throws SwitchyardException 实例没有可回放的模型录制（SW_BAD_REQUEST）
     */
    public TraceDiff replay(String flowInstanceId) {
        FlowInstance original = store.loadInstance(flowInstanceId);
        FlowDefinition definition = definitionOf(original.flowId());
        List<ReplayBlob> blobs =
                replayRecorder == null ? List.of() : replayRecorder.listBlobs(flowInstanceId);
        if (blobs.isEmpty()) {
            throw new SwitchyardException(
                    ErrorCode.SW_BAD_REQUEST,
                    "实例 " + flowInstanceId + " 没有可回放的模型录制（回放依赖响应注入，见 ADR-0004）",
                    null);
        }
        InMemoryReplayStore blobStore = new InMemoryReplayStore();
        blobs.forEach(blobStore::save);
        ReplayChatModel replayModel = new ReplayChatModel(blobStore, flowInstanceId);
        ChatGateway replayGateway =
                (context, modelId, prompt) -> {
                    ChatResponse response = replayModel.call(new Prompt(prompt));
                    String text =
                            response.getResult() == null || response.getResult().getOutput() == null
                                    ? ""
                                    : response.getResult().getOutput().getText();
                    long inputTokens = 0;
                    long outputTokens = 0;
                    if (response.getMetadata() != null
                            && response.getMetadata().getUsage() != null) {
                        inputTokens =
                                response.getMetadata().getUsage().getPromptTokens() == null
                                        ? 0
                                        : response.getMetadata().getUsage().getPromptTokens();
                        outputTokens =
                                response.getMetadata().getUsage().getCompletionTokens() == null
                                        ? 0
                                        : response.getMetadata().getUsage().getCompletionTokens();
                    }
                    return new ChatReply(text, Usage.vendorReported(inputTokens, outputTokens));
                };

        InMemoryStepStore replayStore = new InMemoryStepStore();
        // 回放执行器：隔离存储 + 注入式模型 + 不触发真实审批/补偿
        FlowExecutor replayExecutor =
                new FlowExecutor(
                        replayStore,
                        handlers,
                        new io.github.nephets.agentswitchyard.core.approval.ApprovalGate(
                                new io.github.nephets.agentswitchyard.core.approval
                                        .InMemoryApprovalStore(),
                                new io.github.nephets.agentswitchyard.core.approval
                                        .RoleApprovalAuthorizer(),
                                null,
                                clock),
                        compensationChain,
                        replayGateway,
                        new io.github.nephets.agentswitchyard.core.retry.DefaultErrorClassifier(),
                        clock,
                        null);
        replayExecutor.register(definition);
        replayStore.createInstance(
                new FlowInstance(
                        flowInstanceId,
                        original.flowId(),
                        original.flowVersion(),
                        original.tenantId(),
                        original.traceId(),
                        FlowState.PENDING,
                        original.input(),
                        clock.instant(),
                        clock.instant()));
        replayExecutor.runFlowSync(flowInstanceId, true);
        return new ReplayComparator()
                .compare(store.listSteps(flowInstanceId), replayStore.listSteps(flowInstanceId));
    }

    /** 审批决策回调（由 ApprovalGate 在 CAS 赢家线程上调用，同一决策只触发一次）。 */
    @Override
    public void onDecided(ApprovalRecord record, boolean approved, boolean degraded) {
        if (approved) {
            LOG.info(
                    "审批通过，恢复执行 instId={} stepId={} approvalId={} degraded={}",
                    record.flowInstanceId(),
                    record.stepId(),
                    record.approvalId(),
                    degraded);
            // 挂起步骤回到 RUNNING，runFlowSync 的幂等检查放行并执行该步 handler
            store.markStepResumed(record.flowInstanceId(), record.stepId(), clock.instant());
            Thread.ofVirtual()
                    .name("swy-approve-" + record.flowInstanceId())
                    .start(() -> runFlowSync(record.flowInstanceId(), false));
        } else {
            LOG.info(
                    "审批拒绝，触发补偿 instId={} stepId={} approvalId={} approver={} note={}",
                    record.flowInstanceId(),
                    record.stepId(),
                    record.approvalId(),
                    record.approver(),
                    record.note());
            Thread.ofVirtual()
                    .name("swy-reject-" + record.flowInstanceId())
                    .start(
                            () ->
                                    compensateAndClose(
                                            record.flowInstanceId(),
                                            new SwitchyardException(
                                                    ErrorCode.SW_APPROVAL_DENIED,
                                                    "审批被拒绝: "
                                                            + record.approvalId()
                                                            + "（审批人 "
                                                            + record.approver()
                                                            + "，意见："
                                                            + record.note()
                                                            + "）",
                                                    null)));
        }
    }

    // ==================== 内部执行路径 ====================

    /**
     * 同步执行一轮流程（从第一个未成功步骤开始；审批挂起时返回）。
     *
     * @param flowInstanceId 实例 ID
     * @param replay 是否回放模式（回放跳过审批挂起与状态写入，handler 收到 replay 属性）
     */
    private void runFlowSync(String flowInstanceId, boolean replay) {
        FlowInstance instance = store.loadInstance(flowInstanceId);
        FlowDefinition definition = definitionOf(instance.flowId());
        try {
            if (!replay) {
                // 正常启动 PENDING→RUNNING；审批恢复 WAITING_APPROVAL→RUNNING；崩溃恢复已是 RUNNING 则跳过
                boolean entered =
                        store.compareAndSetInstanceState(
                                flowInstanceId,
                                FlowState.PENDING,
                                FlowState.RUNNING,
                                clock.instant());
                if (!entered) {
                    store.compareAndSetInstanceState(
                            flowInstanceId,
                            FlowState.WAITING_APPROVAL,
                            FlowState.RUNNING,
                            clock.instant());
                }
            }
            for (StepDefinition step : definition.steps()) {
                Optional<StepRecord> existing = store.findStep(flowInstanceId, step.stepId());
                if (existing.isPresent() && existing.get().state().succeeded()) {
                    continue; // ★ 幂等：已成功步骤直接跳过（崩溃恢复不重复副作用）
                }
                if (step.requiresApproval() && !replay) {
                    StepState existingState = existing.map(StepRecord::state).orElse(null);
                    if (existingState == StepState.WAITING_APPROVAL) {
                        return; // 进程重启后保持挂起，续跑由审批回调触发
                    }
                    if (existingState == null) {
                        suspendForApproval(instance, step);
                        return; // 让出执行权，等审批回调
                    }
                    // existingState == RUNNING：审批已通过（markStepResumed），
                    // 决策 CAS 保证本线程是唯一续跑者 → 直接接管执行
                    runOneStep(step, flowInstanceId, replay, true);
                    continue;
                }
                runOneStep(step, flowInstanceId, replay, false);
            }
            if (!replay) {
                store.updateInstanceState(flowInstanceId, FlowState.SUCCEEDED, clock.instant());
                LOG.info("流程完成 instId={} flow={}", flowInstanceId, instance.flowId());
            }
        } catch (FlowStepException fse) {
            if (replay) {
                // 回放中重放历史失败：记录差异由 comparator 处理，不触发真实补偿
                LOG.warn("回放中步骤失败 stepId={} err={}", fse.step().stepId(), fse.getMessage());
                return;
            }
            handleStepFailure(flowInstanceId, fse);
        }
    }

    /** 审批挂起：落步骤记录 + 建审批 + 推待办 + 实例置挂起态。 */
    private void suspendForApproval(FlowInstance instance, StepDefinition step) {
        StepRecord candidate = initialRecord(instance, step);
        store.tryBeginStep(candidate);
        ApprovalRequest request =
                new ApprovalRequest(
                        instance.flowInstanceId(),
                        step.stepId(),
                        step.approvalRole(),
                        step.description() == null
                                ? ("等待 " + step.stepId() + " 审批")
                                : step.description(),
                        ApprovalTimeoutPolicy.HOLD_FOREVER,
                        Duration.ZERO,
                        clock.instant());
        String approvalId = approvalGate.suspend(request);
        store.markWaitingApproval(
                instance.flowInstanceId(), step.stepId(), approvalId, clock.instant());
        store.compareAndSetInstanceState(
                instance.flowInstanceId(),
                FlowState.RUNNING,
                FlowState.WAITING_APPROVAL,
                clock.instant());
        LOG.info(
                "流程挂起等待审批 instId={} stepId={} approvalId={}",
                instance.flowInstanceId(),
                step.stepId(),
                approvalId);
    }

    /** 执行单个步骤（幂等开始 → 重试执行 → 原子落库）。 */
    private void runOneStep(StepDefinition step, String flowInstanceId, boolean replay) {
        runOneStep(step, flowInstanceId, replay, false);
    }

    /**
     * 执行单个步骤。
     *
     * @param adoptExisting true 表示接管已有的 RUNNING 记录直接执行（审批通过续跑路径： 决策 CAS 已保证唯一触发者，插入式幂等判定不适用 ——
     *     记录早已存在）
     */
    private void runOneStep(
            StepDefinition step, String flowInstanceId, boolean replay, boolean adoptExisting) {
        FlowInstance instance = store.loadInstance(flowInstanceId);
        String idemKey = step.resolveIdempotencyKey(instance.input());
        StepRecord candidate;
        if (adoptExisting) {
            candidate = store.findStep(flowInstanceId, step.stepId()).orElse(null);
            if (candidate == null || candidate.state() != StepState.RUNNING) {
                return; // 记录缺失或已被并发完成：不重复执行
            }
        } else {
            candidate =
                    new StepRecord(
                            flowInstanceId,
                            step.stepId(),
                            step.order(),
                            step.kind(),
                            idemKey,
                            StepState.RUNNING,
                            TraceContext.current().newChild().spanId(),
                            Json.write(instance.input()),
                            null,
                            null,
                            null,
                            0,
                            clock.instant(),
                            null,
                            null,
                            null);
            // ★ 幂等判定点：并发/重复提交时只有一个执行权；其余拿到已成功的记录直接复用
            Optional<StepRecord> hit = guard.begin(flowInstanceId, candidate);
            if (hit.isPresent()) {
                return;
            }
        }
        StepHandler handler = handlers.get(step.stepId());
        if (handler == null) {
            throw new FlowStepException(
                    step,
                    candidate,
                    new SwitchyardException(
                            ErrorCode.SW_BAD_REQUEST,
                            "步骤 " + step.stepId() + " 未绑定 StepHandler",
                            null));
        }
        Map<String, Object> attributes = new HashMap<>();
        if (replay) {
            attributes.put("replay", Boolean.TRUE);
        }
        StepContext context =
                new StepContext(
                        flowInstanceId,
                        instance.flowId(),
                        step.stepId(),
                        step.order(),
                        instance.traceId(),
                        candidate.spanId(),
                        instance.input(),
                        attributes,
                        chatGateway);
        try {
            StepResult result = stepExecutor.execute(step, handler, context);
            StepRecord completed =
                    candidate.succeeded(
                            Json.write(result.output()),
                            result.usage(),
                            result.costCny(),
                            clock.instant());
            store.completeStep(completed, clock.instant());
        } catch (Exception ex) {
            throw new FlowStepException(step, candidate, ex);
        }
    }

    /** 失败路径：落失败状态 → 反向补偿 → 终态。 */
    private void handleStepFailure(String flowInstanceId, FlowStepException fse) {
        store.markFailed(
                flowInstanceId, fse.step().stepId(), fse.getCause().toString(), clock.instant());
        compensateAndClose(flowInstanceId, fse.getCause());
    }

    /**
     * 补偿并关闭流程：置 COMPENSATING → 反向补偿 → COMPENSATED / COMPENSATE_FAILED。
     *
     * <p>★ 补偿失败<b>不掩盖原始异常</b>（架构 §8.4）：原始异常作为主异常打进日志与报告， 补偿失败全部挂 suppressed。
     *
     * @param flowInstanceId 实例 ID
     * @param originalCause 触发补偿的原始异常
     */
    private void compensateAndClose(String flowInstanceId, Throwable originalCause) {
        try {
            store.updateInstanceState(flowInstanceId, FlowState.COMPENSATING, clock.instant());
            CompensationReport report =
                    compensationChain.run(store.listCompletedSteps(flowInstanceId));
            report.entries()
                    .forEach(
                            entry ->
                                    store.markCompensated(
                                            flowInstanceId,
                                            entry.stepId(),
                                            entry.outcome().success(),
                                            clock.instant()));
            compensationReports.put(flowInstanceId, report);
            store.updateInstanceState(
                    flowInstanceId,
                    report.allSucceeded() ? FlowState.COMPENSATED : FlowState.COMPENSATE_FAILED,
                    clock.instant());
            FlowExecutionException ex =
                    new FlowExecutionException(
                            "流程 " + flowInstanceId + " 失败并触发补偿（原始异常为主异常）", originalCause);
            report.entries().stream()
                    .filter(entry -> !entry.outcome().success())
                    .forEach(
                            entry ->
                                    ex.addSuppressed(
                                            new SwitchyardException(
                                                    ErrorCode.SW_INTERNAL,
                                                    "补偿失败 step="
                                                            + entry.stepId()
                                                            + " action="
                                                            + entry.actionRef()
                                                            + " err="
                                                            + entry.outcome().error(),
                                                    null)));
            LOG.warn(
                    "流程已补偿关闭 instId={} allSucceeded={} original={}",
                    flowInstanceId,
                    report.allSucceeded(),
                    originalCause.getMessage());
        } catch (RuntimeException closeFailure) {
            // 补偿路径自身崩溃：至少保证流程进入 COMPENSATE_FAILED 终态告警
            LOG.warn("补偿关闭路径异常 instId={} err={}", flowInstanceId, closeFailure.getMessage());
            store.updateInstanceState(flowInstanceId, FlowState.COMPENSATE_FAILED, clock.instant());
        }
    }

    private StepRecord initialRecord(FlowInstance instance, StepDefinition step) {
        return new StepRecord(
                instance.flowInstanceId(),
                step.stepId(),
                step.order(),
                step.kind(),
                step.resolveIdempotencyKey(instance.input()),
                StepState.RUNNING,
                TraceContext.current().newChild().spanId(),
                Json.write(instance.input()),
                null,
                null,
                null,
                0,
                clock.instant(),
                null,
                null,
                null);
    }

    private FlowDefinition definitionOf(String flowId) {
        FlowDefinition definition = definitions.get(flowId);
        if (definition == null) {
            throw new SwitchyardException(ErrorCode.SW_BAD_REQUEST, "流程未注册: " + flowId, null);
        }
        return definition;
    }
}
