# agentswitchyard

<div align="center">

**The reliability layer for LangGraph4j — enterprise approvals · compensating transactions · deterministic replay**

`0.1.0` · Java 21 · Spring Boot 3.5.16 · Apache-2.0

</div>

---

> **我们做 LangGraph4j 的可靠性层，不替代它。**
> *(We are the reliability layer for LangGraph4j — we do not replace it.)*

LangGraph4j orchestrates your graph; `agentswitchyard` covers the three things it leaves to you:

| Capability | One-liner | PRD |
|------|--------|----------|
| 🏢 **Enterprise approvals** (Human-in-the-loop) | Suspensions are **durable**: approvals survive restarts — even `kill -9`. Decisions carry role rosters, full audit notes, and concurrent decides apply exactly once | P0-4 |
| 🔄 **Compensating transactions** (Saga) | On failure, executed side effects are undone in **reverse order**; compensation failures never mask the original exception | P0-3 |
| 🎯 **Deterministic replay** | Record the model's **original responses**, then inject them verbatim on replay — show the regulator exactly what happened, with **zero upstream calls** | P0-5 |

One closed loop: **crash mid-flight → no duplicated side effects → reverse what already ran → a human signs off → replay the exact original for the regulator.**

---

## Why this isn't yet another workflow engine

- **It does not replace LangGraph4j.** You keep writing your graph. We wrap it in a reliability shell: per-step ledger (`step_record`), idempotency, an approval gate, a compensation chain, and a replay engine.
- **The idempotency key lives in the same table, same transaction as the step record.** No classic "check-then-execute-then-write" race: concurrent same-key submissions are arbitrated by the database itself (unique index `(flow_instance_id, step_id, idem_key)` + dialect-specific idempotent INSERT). **The insert *is* the decision.**
- **Determinism comes from response injection, not model snapshots.** A snapshot can't freeze output (models drift within a version). The only way to reproduce "what actually happened" is to record the original response and inject it verbatim (ADR-0004).
- **Zero Spring dependencies in core.** `agentswitchyard-core` is pure logic (single exception: the Spring AI model SPI — ADR-0001), with four storage dialects out of the box (H2 / MySQL / PostgreSQL / DM8).

## Quick start (30 seconds)

```bash
git clone https://github.com/nephets/agentswitchyard.git
cd agentswitchyard
./mvnw -q clean verify          # full test suite (incl. a real kill -9 crash-recovery IT and four-dialect ITs)
./mvnw -pl sample spring-boot:run
```

```bash
# Start a 500k CNY credit application
curl -X POST localhost:8080/demo/flow/start \
     -H 'Content-Type: application/json' \
     -d '{"ref":"R-1","amount":500000}'
# → The flow suspends at "manual review" (the suspension is durable)

# Approve as role risk-officer
curl -X POST localhost:8080/_switchyard/approval/<approvalId>/decide \
     -H 'Content-Type: application/json' \
     -d '{"approver":"zhang-san","approverRole":"risk-officer","approved":true,"note":"OK"}'
# → Instance resumes to SUCCEEDED; exactly one disbursement row in the side-effect ledger
```

Or use the CLI:

```bash
swy run --flow credit-approve --input '{"ref":"R-1","amount":500000}'
swy approve <approvalId> --approver zhang-san --role risk-officer -y --note "OK"
swy replay <instanceId>
swy doctor
```

## State machine (11 states, no graph — ADR-0002)

```
PENDING → RUNNING → SUCCEEDED
   │         │
   │         ├──→ WAITING_APPROVAL ──(approve)──→ RUNNING
   │         │              └──(reject/timeout)──→ REJECTED → COMPENSATING
   │         ├──→ RETRYING → RUNNING
   │         └──→ FAILED → COMPENSATING → COMPENSATED
   │                              └──→ COMPENSATE_FAILED (page a human)
   └──→ CANCELLED
```

Illegal transitions throw before they hit the database — corrupted state never lands quietly.

## Support status (honest disclosure)

| Component | Version | Status |
|------|------|------|
| Java | 21 (LTS) | ✅ Fully supported |
| Spring Boot | 3.5.16 | ⚠️ **OSS support for 3.5.x ended 2026-06-30**; commercial support until 2032-06-30. We pin 3.5.16 (final 3.5 patch) for all fixes; upgrade path in [ADR-0005](docs/adr/0005-upgrade-path.md) (Boot 4.1 / Spring AI 2.0) |
| Spring AI | 1.1.8 | ✅ |
| LangGraph4j | 1.8.14 | 🚧 **Not wired in v0.1** (version reserved in the POM only); see [ADR-0002](docs/adr/0002-not-a-graph-engine.md) |
| Storage | H2 / MySQL 8.4 / PostgreSQL 16 / DM8 | ✅ Four dialects (DM8 verified at the SQL-generation layer; production onboarding runs the same DDL via DM's official Flyway fork) |
| A2A network transport | — | 🚧 P1. v0.1 ships a single-machine envelope + JSON Schema validation (`POST /_switchyard/protocol/validate`) |
| Sandbox | registration + deny-unregistered + domain allowlist + approval declaration for high-risk tools | ✅ Three gates; full process-level sandbox is P1 |
| Console UI | — | 🚧 P1. v0.1 ships JSONL / Markdown export (`GET /_switchyard/trace/{id}/jsonl`, `/report`) |

## Architecture at a glance

```
┌─────────────────────────────────────────────────────┐
│ sample (credit-approval demo: your handlers here)   │
├─────────────────────────────────────────────────────┤
│ starter (auto-config + /_switchyard REST + approval │
│          webhook + CLI + Spring AI gateway +        │
│          crash-recovery runner)                     │
├──────────────────────────┬──────────────────────────┤
│ core (zero Spring deps)  │ store (JDBC × 4 dialects)│
│  FlowExecutor (vthreads) │  JdbcStepStore           │
│  IdempotencyGuard        │  JdbcApprovalStore (CAS) │
│  ApprovalGate            │  TraceBlobStore (replay) │
│  CompensationChain (Saga)│  Flyway V1–V3            │
│  ReplayChatModel         │                          │
│  ToolSandbox (3 gates)   │                          │
├──────────────────────────┴──────────────────────────┤
│ contract (shared contract: classes + contract-v1.json)│
└─────────────────────────────────────────────────────┘
```

Contracts (traceId / Usage / audit events / envelope / span attributes) are shared **by spec** with sibling repos `llm-switchyard` and `llm-glasshouse` (shared contract, shared jars — never). Repo-exclusive span attributes: `swy.flow.instance.id`, `swy.step.*`, `swy.approval.state`.

## Modules

| Module | Description |
|------|------|
| `agentswitchyard-core` | Flow definition / execution engine / idempotency / approvals / compensation / replay / tool governance / trace export. Zero framework deps except the Spring AI model SPI |
| `agentswitchyard-store` | JDBC implementations of StepStore / ApprovalStore / ReplayStore (H2/MySQL/PG/DM8) + Flyway migrations |
| `agentswitchyard-spring-boot-starter` | Auto-configuration, `/_switchyard` REST API, approval webhook, picocli CLI, Spring AI gateway |
| `mock-llm` | Deterministic OpenAI-compatible mock model (CI: zero real API calls, zero cost) |
| `agentswitchyard-sample` | Credit-approval demo + a real kill -9 crash-recovery IT |

## Configuration cheat sheet

```yaml
agentswitchyard:
  flow-definitions:
    - classpath:flows/credit-approve.yaml   # declare flows in YAML (maintainable by ops)
  approval:
    rosters:
      risk-officer: [zhang-san, li-si]      # role rosters (optional; default checks role match only)
  recovery:
    enabled: true                           # auto-resume kill -9 leftovers on boot
```

Business handlers align by bean name to `stepId`; compensation actions by bean name to the `compensation` declaration:

```java
@Bean("disburse") StepHandler disburse() { return ctx -> { ... }; }
@Bean CompensationAction cancelReserve() { return executed -> ...; }
```

## Docs

- [Architecture](docs/architecture.md) · [Approvals](docs/approval.md) · [Compensation](docs/compensation.md) · [Replay](docs/replay.md)
- ADRs: [0001 core zero-Spring](docs/adr/0001-core-zero-spring.md) · [0002 not a graph engine](docs/adr/0002-not-a-graph-engine.md) · [0003 no cross-repo deps](docs/adr/0003-no-cross-repo-deps.md) · [0004 replay by injection](docs/adr/0004-replay-by-injection.md) · [0005 upgrade path](docs/adr/0005-upgrade-path.md)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Before opening a PR run `./mvnw -B spotless:check` and the full `./mvnw clean verify` (CI uses the same standard and makes zero real API calls).

## License

[Apache-2.0](LICENSE)
