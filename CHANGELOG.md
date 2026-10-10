# Changelog

本仓遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [SemVer 2.0.0](https://semver.org/lang/zh-CN/)。

## [0.1.0] - 2026-10-10

首版。三条 P0 构成完整闭环：**跑到一半挂了 → 不重复副作用 → 能反向撤销 → 有人签字 → 能原样重放给监管看。**

### Added（可靠性层三件事）

- **企业审批（HITL）**：审批挂起持久化（kill -9 后仍在待办）；角色花名册授权；审批意见完整留痕；并发决策 CAS 只生效一次（HTTP `applied` 字段可观测）；四种超时策略（AUTO_APPROVE / AUTO_REJECT / AUTO_DEGRADE / HOLD_FOREVER，可注入 Clock）；Webhook 入口 `POST /_switchyard/approval/{id}/decide`。
- **补偿事务（Saga）**：反向顺序补偿链 + `CompensationReport`；补偿失败不掩盖原始异常（suppressed）；补偿失败不中断其余补偿；`COMPENSATE_FAILED` 人工介入终态。
- **确定性回放**：响应录制 + 注入（ADR-0004：确定性来自响应注入，不是模型快照）；回放零上游调用（WireMock verify(0) 钉死）；隔离内存存储零真实副作用；`TraceDiff.identical` 机器可判。

### Added（引擎与基础设施）

- FlowExecutor：虚拟线程执行模型、11 态状态机白名单转移（写入前 fail-fast）、退避重试（可插拔 ErrorClassifier）。
- 幂等：幂等键与步骤记录**同表同事务**，方言化幂等 INSERT「插入即判定」；并发输家等待赢家结果后复用（20 线程并发单副作用验收）。
- 存储：JDBC 四方言（H2 / MySQL 8.4 / PostgreSQL 16 / 达梦 DM8）+ Flyway V1-V3 迁移 + Testcontainers MySQL/PG IT。
- 崩溃恢复：`recoverInterrupted` 启动自动续跑 + `resume` 手动入口 + 真实 kill -9 端到端 IT（`CrashResumeIT`，ProcessBuilder + destroyForcibly）。
- 工具治理三道闸：显式注册（拒绝未注册）、空 allowedDomains 禁止全部外呼、高危工具无审批声明注册期拒绝。
- 消息协议：AgentEnvelope + JSON Schema 校验（`POST /_switchyard/protocol/validate`）。
- 观测：`swy.` 前缀 span 属性（本仓独有 `swy.flow.instance.id` / `swy.step.*` / `swy.approval.state`）、EVT-001 JSONL 导出、Markdown 轨迹报告（每步耗时 + 成本）。
- starter：自动配置、`/_switchyard` REST、picocli CLI（run / approve / replay / doctor）、Spring AI 网关（带录制）、健康检查。
- mock-llm：OpenAI 兼容确定性 mock（CI 零真实 API / 零成本）。
- sample：信贷审批 demo + 真实 kill -9 崩溃恢复 IT + 审批 / 补偿 / 回放全流程 IT。
- CI：全量 verify + spotless + workflow 语法校验 + 契约一致性 + 架构边界 + compose 冒烟，全程零真实 API 调用。

### Known limitations（诚实声明）

- 首版未引入 LangGraph4j（版本号已预留，见 ADR-0002）。
- 达梦方言经 SQL 生成层验证（无社区版 Testcontainers/Flyway 支撑）。
- A2A 网络传输为 P1（首版单机信封 + JSON Schema 校验）。
- 完整进程级沙箱为 P1（首版三道闸：注册 / 域名白名单 / 审批声明）。
- 控制台 UI 为 P1（首版 JSONL + Markdown 导出）。
- Spring Boot 3.5.x OSS 支持已于 2026-06-30 结束（商业支持至 2032-06-30，升级路径见 ADR-0005）。
