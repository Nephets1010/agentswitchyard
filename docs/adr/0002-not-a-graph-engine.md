# ADR-0002：本仓不做图编排引擎（状态机 11 态足够）

- 状态：已接受
- 日期：2026-10-10

## 背景

「可靠性层」听上去需要一个执行引擎，最容易滑向的坑是自己再写一个 DAG/图编排器，与 LangGraph4j 正面竞争。

## 决策

1. 流程执行模型收敛为**线性有序步骤 + 11 态状态机**（`FlowState.allowedTargets` 白名单转移）；
2. 图结构（分支、循环、并行）仍归 LangGraph4j；本仓只在图的**节点外**包可靠性壳；
3. 首版**不引入 LangGraph4j 依赖**（pom 仅预留 `langgraph4j.version` 属性）—— 接入面设计为：LangGraph4j 的节点回调里调 `FlowExecutor.start/resume`，或用 YAML 声明线性子流程。

## 后果

- 11 态状态机的非法转移可在写入前 fail-fast，比通用图校验便宜且可靠；
- 不承诺表达任意拓扑 —— 这是**能力边界**而不是缺陷：需要分叉并行的业务，把分叉留在图里，把「每条边的可靠性」交给我们；
- 未来引入 LangGraph4j 时（版本号已预留），集成点是 `interrupt` → `ApprovalGate.suspend`、`checkpoint` → `StepStore`，不改变本仓 SPI。
