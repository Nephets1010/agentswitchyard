# 架构说明

> 本文对应架构设计文档 §5（repo②）。一句话定位：**我们做 LangGraph4j 的可靠性层，不替代它。**

## 1. 模块分层与依赖方向

```
sample → starter → store → core ← contract
                      ↑ (SPI 定义在 core，实现供 starter 装配)
```

- **contract**：三仓共享的契约类与 `contract/contract-v1.json`（契约共享，jar 不共享）。本仓独有 span 属性：`swy.flow.instance.id`、`swy.step.*`、`swy.approval.state`。
- **core**：纯逻辑。除 Spring AI 的模型 SPI（`ReplayChatModel implements ChatModel` 需要，见 ADR-0001）外零框架依赖，由 maven-enforcer 的 bannedDependencies 强制。
- **store**：StepStore / ApprovalStore / ReplayStore 的 JDBC 实现（H2 / MySQL / PostgreSQL / 达梦四方言）+ Flyway 迁移。
- **starter**：Spring Boot 自动配置、`/_switchyard` REST、审批 Webhook、CLI、Spring AI 网关、崩溃恢复 runner。
- **sample**：业务 handler + 真实 kill -9 崩溃恢复 IT。

## 2. 执行模型

- 每个流程实例跑在自己的**虚拟线程**上，步骤顺序执行。
- 每步执行前经 `IdempotencyGuard.begin`：`tryBeginStep` 用方言化幂等 INSERT 对 `step_record` 做原子插入，**插入成功 = 赢得执行权**（以返回对象引用相等判定）。
- 并发输家**等待赢家出结果**：赢家成功 → 复用其输出（副作用不重复）；赢家失败 → 输家获得重试权。等待上限 15s（崩溃残留由 `failInterruptedSteps` 置 FAILED 兜底）。
- 审批步骤：落步骤记录 → 建审批（持久化）→ 实例置 `WAITING_APPROVAL` → 让出虚拟线程。决策 CAS 生效后回调 `FlowExecutor.onDecided`：approved → `markStepResumed` → 新虚拟线程续跑；rejected → 反向补偿。

## 3. 状态机

11 个状态、白名单转移（`FlowState.allowedTargets`）。非法转移在写入前抛异常。详见 README 的状态机图。

## 4. 存储契约（StepStore SPI）

| 方法 | 契约 |
|------|------|
| `tryBeginStep(candidate)` | 原子幂等插入；冲突键 `(flow_instance_id, step_id, idem_key)` 唯一索引；插入成功必须返回 candidate 本身 |
| `compareAndSetInstanceState` | CAS；期望状态不符返回 false，非法转移抛异常 |
| `failInterruptedSteps` | RUNNING 残留 → FAILED（崩溃恢复前置） |
| `markStepResumed` | WAITING_APPROVAL → RUNNING（审批通过续跑） |

四方言的幂等 INSERT 收敛在 `Dialect` 枚举：MySQL `INSERT IGNORE`、PostgreSQL `ON CONFLICT DO NOTHING`、H2 标准 `MERGE USING`（H2 2.x 不支持 ON CONFLICT，专有 `MERGE...KEY` 又是覆盖式 upsert）、达梦 `MERGE INTO ... USING (SELECT ? FROM dual)`。

## 5. 崩溃恢复（kill -9）

1. 子进程被 `destroyForcibly()` 杀死（CrashResumeIT，真实信号）；
2. 重启后 starter 的 `ApplicationRunner` 调 `FlowExecutor.recoverInterrupted()`：所有 RUNNING 残留实例 → `failInterruptedSteps` → 从第一个未成功步骤续跑；
3. `WAITING_APPROVAL` 实例保持挂起（审批记录持久化），续跑由审批回调触发；
4. 已成功步骤被幂等守卫直接跳过 —— **副作用不重复**。

## 6. 观测性

- span 属性按 SPN-001（`swy.` 前缀），traceId 为三仓同源主键；
- `GET /_switchyard/trace/{id}` 轨迹 JSON、`/jsonl` EVT-001 事件流、`/report` Markdown 报告（每步耗时 + 成本）；
- 回放差异 `TraceDiff` 支持「与历史完全一致」的机器可判结论。

## 7. 与姊妹仓库的边界

- `llm-switchyard`（仓库①）：LLM 网关的配额 / 降级 / 成本归一 —— 共享契约，不共享 jar；
- `llm-glasshouse`（仓库③）：span 管线与可视化 —— 消费本仓的 JSONL 导出；
- 三仓之间**零代码依赖**（ADR-0003），集成只走 HTTP 与契约字段。
