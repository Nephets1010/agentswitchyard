# ADR-0003：三仓零代码依赖（共享契约规范，不共享 jar）

- 状态：已接受
- 日期：2026-10-10

## 背景

`llm-switchyard`（①网关）、`agentswitchyard`（②可靠性层）、`llm-glasshouse`（③观测）三仓由同一团队孵化，最容易发生的耦合事故是「顺手 import 一个 ① 的类」—— 之后版本节奏就被锁死。

## 决策

1. 三仓之间 **zero code dependency**：pom 互不依赖，import 互不出现；
2. 共享的只有**契约**：`contract/contract-v1.json`（字段级 schema）+ 对应的契约 Java 类（各仓拷贝同源文件，包名按仓收敛为本仓 namespace）；
3. 集成只走两种通道：HTTP（traceparent / baggage 头透传）与 JSONL 导出（EVT-001）；
4. CI 有契约一致性 job：各仓的 contract-v1.json 字段级 diff + 本仓独有 span 属性断言（`swy.flow.instance.id` / `swy.step.*` / `swy.approval.state` 只允许出现在本仓）。

## 后果

- 每仓可独立发版、独立归档、独立死亡；
- 代价：契约演进必须走 PRD 的契约评审流程（改 schema = 三仓同批），不能顺手改；
- traceId 是三仓同源主键：一笔请求在三个系统里的痕迹用同一个 traceId 归并。
