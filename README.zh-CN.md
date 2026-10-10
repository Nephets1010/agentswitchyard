# agentswitchyard

<div align="center">

**LangGraph4j 的可靠性层 —— 企业审批 · 补偿事务 · 确定性回放**

`0.1.0` · Java 21 · Spring Boot 3.5.16 · Apache-2.0

</div>

---

> **我们做 LangGraph4j 的可靠性层，不替代它。**

LangGraph4j 负责把图编排好；`agentswitchyard` 只做三件 LangGraph4j 不替你兜底的事：

| 能力 | 一句话 | 对应 PRD |
|------|--------|----------|
| 🏢 **企业审批**（Human-in-the-loop） | 审批挂起是**持久化**的：进程重启、甚至 kill -9 后，审批还在待办里；决策有角色花名册、有意见留痕、并发决策只生效一次 | P0-4 |
| 🔄 **补偿事务**（Saga） | 流程失败后按**反向顺序**撤销已执行的副作用；补偿失败不掩盖原始异常 | P0-3 |
| 🎯 **确定性回放** | 把当时模型的**原始响应**录下来，回放时原样注入 —— 监管问「当时为什么批的」，你重放给他看，**零上游调用** | P0-5 |

一句话闭环：**跑到一半挂了 → 不重复副作用 → 能反向撤销 → 有人签字 → 能原样重放给监管看。**

---

## 为什么不是又造一个工作流引擎

- **不替代 LangGraph4j**：你不写图结构，图还是你的。我们在图的**外面**包一层可靠性壳：每一步的账本（step_record）、幂等、审批门、补偿链、回放器。
- **幂等键与步骤记录同表同事务**：不存在「先查幂等表、再执行、再写步骤表」的经典三段式竞态 —— 并发同键提交在数据库层只允许一行插入成功（`step_record` 的 `(flow_instance_id, step_id, idem_key)` 唯一索引 + 方言化幂等 INSERT），**插入本身就是判定**。
- **确定性来自响应注入，不是模型快照**：模型快照无法冻结输出（同版本模型也会漂移）。唯一能 100% 重现「当时结果」的办法，是把当时的响应录下来、回放时原样注入（ADR-0004）。
- **核心层零 Spring 依赖**：`agentswitchyard-core` 只有逻辑（唯一例外是 Spring AI 的模型 SPI，见 ADR-0001），四个存储方言（H2 / MySQL / PostgreSQL / 达梦）开箱即用。

## 30 秒上手

```bash
git clone https://github.com/nephets/agentswitchyard.git
cd agentswitchyard
./mvnw -q clean verify          # 全量测试（含真实 kill -9 崩溃恢复 IT、四方言 IT）
./mvnw -pl sample spring-boot:run
```

```bash
# 启动一笔 50 万授信申请
curl -X POST localhost:8080/demo/flow/start \
     -H 'Content-Type: application/json' \
     -d '{"ref":"R-1","amount":500000}'
# → 流程跑到「人工复核」自动挂起（审批是持久化的）

# risk-officer 角色审批通过
curl -X POST localhost:8080/_switchyard/approval/<approvalId>/decide \
     -H 'Content-Type: application/json' \
     -d '{"approver":"zhang-san","approverRole":"risk-officer","approved":true,"note":"同意"}'
# → 实例续跑到 SUCCEEDED，副作用台账恰有一行放款
```

CLI 也可以：

```bash
swy run --flow credit-approve --input '{"ref":"R-1","amount":500000}'
swy approve <approvalId> --approver zhang-san --role risk-officer -y --note "同意"
swy replay <instanceId>
swy doctor
```

## 状态机（只有 11 个状态，不做图 —— ADR-0002）

```
PENDING → RUNNING → SUCCEEDED
   │         │
   │         ├──→ WAITING_APPROVAL ──(approve)──→ RUNNING
   │         │              └──(reject/timeout)──→ REJECTED → COMPENSATING
   │         ├──→ RETRYING → RUNNING
   │         └──→ FAILED → COMPENSATING → COMPENSATED
   │                              └──→ COMPENSATE_FAILED（人工介入告警）
   └──→ CANCELLED
```

非法转移在写入前就抛异常 —— 不让错乱的状态悄悄写进数据库。

## 支持状态（诚实声明）

| 组件 | 版本 | 状态 |
|------|------|------|
| Java | 21 (LTS) | ✅ 完整支持 |
| Spring Boot | 3.5.16 | ⚠️ **3.5.x 的 OSS 支持已于 2026-06-30 结束**；商业支持至 2032-06-30。本仓锁 3.5.16（3.5 最终补丁）以享受全部修复；升级路径见 [ADR-0005](docs/adr/0005-upgrade-path.md)（Boot 4.1 / Spring AI 2.0） |
| Spring AI | 1.1.8 | ✅ |
| LangGraph4j | 1.8.14 | 🚧 **首版未引入**（仅 pom 预留版本号）；见 [ADR-0002](docs/adr/0002-not-a-graph-engine.md) |
| 存储 | H2 / MySQL 8.4 / PostgreSQL 16 / 达梦 DM8 | ✅ 四方言（达梦经 SQL 生成层验证，生产接入用达梦官方 Flyway 分支执行同一 DDL） |
| A2A 网络传输 | — | 🚧 P1。首版为单机信封 + JSON Schema 校验（`POST /_switchyard/protocol/validate`） |
| 沙箱 | 注册 + 拒绝未注册 + 域名白名单 + 高危需审批声明 | ✅ 三道闸；完整进程级沙箱为 P1 |
| 控制台 UI | — | 🚧 P1。首版提供 JSONL / Markdown 导出（`GET /_switchyard/trace/{id}/jsonl`、`/report`） |

## 架构一瞥

```
┌─────────────────────────────────────────────────────┐
│ sample（信贷审批 demo：你的业务 handler 写在这里）    │
├─────────────────────────────────────────────────────┤
│ starter（自动配置 + /_switchyard REST + 审批 Webhook │
│          + CLI + Spring AI 网关 + 崩溃恢复 runner）  │
├──────────────────────────┬──────────────────────────┤
│ core（零 Spring 依赖）    │ store（JDBC 四方言）      │
│  FlowExecutor 虚拟线程    │  JdbcStepStore           │
│  IdempotencyGuard 插入即判定│ JdbcApprovalStore CAS   │
│  ApprovalGate 审批门      │  TraceBlobStore 回放录制  │
│  CompensationChain 反向 Saga│ Flyway V1-V3           │
│  ReplayChatModel 响应注入  │                          │
│  ToolSandbox 三道闸       │                          │
├──────────────────────────┴──────────────────────────┤
│ contract（三仓共享契约：契约类 + contract-v1.json）   │
└─────────────────────────────────────────────────────┘
```

契约（traceId / Usage / 审计事件 / 信封 / span 属性）与姊妹仓库 `llm-switchyard`、`llm-glasshouse` **同源共享**（共享契约规范，不共享 jar）。本仓独有 span 属性：`swy.flow.instance.id`、`swy.step.*`、`swy.approval.state`。

## 模块

| 模块 | 说明 |
|------|------|
| `agentswitchyard-core` | 流程定义 / 执行引擎 / 幂等 / 审批 / 补偿 / 回放 / 工具治理 / 轨迹导出。core 层除 Spring AI 模型 SPI 外零框架依赖 |
| `agentswitchyard-store` | StepStore / ApprovalStore / ReplayStore 的 JDBC 实现（H2/MySQL/PG/达梦）+ Flyway 迁移 |
| `agentswitchyard-spring-boot-starter` | 自动配置、`/_switchyard` REST API、审批 Webhook、picocli CLI、Spring AI 网关 |
| `mock-llm` | OpenAI 兼容的确定性 mock 模型（CI 零成本零真实调用） |
| `agentswitchyard-sample` | 信贷审批 demo + 真实 kill -9 崩溃恢复 IT |

## 配置速查

```yaml
agentswitchyard:
  flow-definitions:
    - classpath:flows/credit-approve.yaml   # YAML 声明流程（非 Java 团队可维护）
  approval:
    rosters:
      risk-officer: [zhang-san, li-si]      # 角色花名册（可选；缺省只校验角色一致）
  recovery:
    enabled: true                           # 启动时自动续跑 kill -9 残留实例
```

业务 handler 以 bean 名对齐 stepId，补偿动作以 bean 名对齐 `compensation` 声明：

```java
@Bean("disburse") StepHandler disburse() { return ctx -> { ... }; }
@Bean CompensationAction cancelReserve() { return executed -> ...; }
```

## 文档

- [架构说明](docs/architecture.md) · [审批](docs/approval.md) · [补偿](docs/compensation.md) · [回放](docs/replay.md)
- ADR：[0001 core 零 Spring](docs/adr/0001-core-zero-spring.md) · [0002 不做图引擎](docs/adr/0002-not-a-graph-engine.md) · [0003 不跨仓依赖](docs/adr/0003-no-cross-repo-deps.md) · [0004 回放靠注入](docs/adr/0004-replay-by-injection.md) · [0005 升级路径](docs/adr/0005-upgrade-path.md)

## 参与贡献

见 [CONTRIBUTING.md](CONTRIBUTING.md)。提交 PR 前请跑 `./mvnw -B spotless:check` 与全量 `./mvnw clean verify`（CI 与本地同一标准，CI 全程零真实 API 调用）。

## 许可证

[Apache-2.0](LICENSE)
