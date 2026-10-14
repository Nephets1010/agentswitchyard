# ADR-0004：确定性来自响应注入，不是模型快照

- 状态：已接受
- 日期：2026-10-10

## 背景

监管与合规复盘要求「原样重放给监管看」。第一个直觉是「冻结当时用的模型快照」。该直觉是错的：

1. 同版本模型也会漂移（供应商调采样参数、安全策略、tokenizer）；
2. 快照无法执行 —— 权重不公开、托管环境不复现；
3. 即使能执行，输出也非确定（温度 > 0）。

## 决策

1. 录制：每次真实模型调用后，把**原始响应**与请求摘要（sha256 of 规范化 prompt）落 `replay_blob`；
2. 回放：`ReplayChatModel implements ChatModel`，按请求摘要命中后把录制响应**原样注入** —— 对 handler 而言与真实调用走同一条 `ctx.chat(...)` 通道；
3. 未命中（strict 模式）抛 `SW_MODEL_UNAVAILABLE`，绝不静默编造响应；
4. 副作用隔离：回放跑在 `InMemoryStepStore` 上，handler 收到 `replay=true` 属性后只重算输出；审批步骤不挂起；失败不触发补偿；
5. 差异 `TraceDiff.identical` 由比较器重算，不信任调用方。

## 后果

- 回放 100% 重现「当时的模型输出」，且**零上游调用**（验收用 WireMock `verify(0, ...)` 钉死）；
- 代价：不录制的调用不可回放（`replay` 抛 `SW_BAD_REQUEST`，报错信息直说 ADR-0004）；
- handler 内部的墙钟 / 随机数 / 外部非录制数据不在保证范围，handler 应用 `ctx.replay()` 自行确定性替代（docs/replay.md 的局限节）。
