# 确定性回放

## 核心立场（ADR-0004）

**确定性来自响应注入，不是模型快照。**

模型快照无法冻结输出 —— 同版本模型也会漂移，供应商随时调整采样参数。唯一能 100% 重现「当时结果」的办法，是把当时模型的**原始响应**录下来，回放时按请求摘要原样注入。

## 录制

生产网关 `SpringAiChatGateway` 在每次真实模型调用后录制：

```
key = (flowInstanceId, spanId, seq)      # 一步可以多次调用模型
requestDigest = sha256(规范化 prompt)     # 回放命中判定
responseJson = {text, usage}
```

落 `replay_blob` 表（`TraceBlobStore`，同主键覆盖写）。

## 回放执行

`FlowExecutor.replay(instanceId)` 在**隔离的** `InMemoryStepStore` 上原样重跑：

| 维度 | 原始执行 | 回放 |
|------|----------|------|
| 存储 | JDBC（真实账本） | 隔离内存存储（真实账本只读不写） |
| 模型 | 真实上游 | `ReplayChatModel` 按摘要命中注入，**零上游调用** |
| 副作用 | handler 真做 | handler 收到 `replay=true` 属性，应只重算输出 |
| 审批 | 挂起等待 | 不挂起，直接执行 handler |
| 失败 | 触发真实补偿 | 记录差异，不触发补偿 |

`ReplayChatModel` 实现了 Spring AI 的 `ChatModel` SPI —— 对 handler 来说，回放与真实执行走同一条 `ctx.chat(...)` 通道，**回放的可信度来自通道一致性**。未命中摘要时 strict 模式抛 `SW_MODEL_UNAVAILABLE`（宁可失败也不静默编造）。

## 差异报告

```json
{"identical": true, "diffs": []}
```

`identical` 由 `ReplayComparator` 重算（不信任调用方），逐步骤对比输出与用量；任何漂移都进 `diffs`。监管问「当时为什么批的」：`POST /_switchyard/replay/{id}` 原样重放给监管看。

## 验收对应

- `ReplayChatModelTest`（WireMock）：命中时 `verify(0, ...)` 零上游调用；
- `FullFlowReplayIT`：真实审批闭环 → 回放 `identical=true`，副作用台账零新增行。

## 局限（诚实声明）

回放重现的是「模型输出 + 确定性规则」的组合结果。handler 内部若依赖墙钟、随机数或外部系统的非录制数据，需要 handler 自己在回放模式下用确定性替代（`ctx.replay()`）。
