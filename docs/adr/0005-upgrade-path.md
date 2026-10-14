# ADR-0005：Spring Boot 3.5.16 的生命周期与升级路径

- 状态：已接受
- 日期：2026-10-10

## 背景（诚实声明）

本仓锁定 **Spring Boot 3.5.16**（3.5 线最终补丁）。生命周期事实：

| 阶段 | 时间 |
|------|------|
| 3.5.x OSS 支持（开源补丁） | **已于 2026-06-30 结束** |
| 3.5.x 商业支持（VMware Tanzu） | 至 2032-06-30 |
| 3.4.x OSS 支持 | 已于 2025-12-31 结束 |

选 3.5.16 而非 3.4 的原因：3.5 是该线的最后一个补丁版本，包含全部 OSS 修复；我们依赖的 Spring AI 1.1.8 基于 Boot 3.5 基线。

## 风险与缓解

- **风险**：OSS 用户不再收到免费安全补丁。
- **缓解 1**：本仓把 Boot 限制在 starter / store / sample / mock-llm 模块，**core 零 Boot 依赖**（ADR-0001）—— 核心逻辑不受生命周期影响；
- **缓解 2**：本仓自带依赖基线（BOM），升级 Boot = 改一处版本号 + 全量回归；
- **缓解 3**：CI 全量测试（含 kill -9 IT、四方言 IT）对任何升级做行为级验证。

## 升级路径

| 目标 | 前置条件 | 预估工作量 |
|------|----------|-----------|
| Boot 3.5.16 → Boot 4.1 | Spring AI 2.0 GA（基于 Boot 4.1 基线）；`spring-ai-client-chat` 的 SPI 包名迁移 | 中：`ChatModel` / `Prompt` import 调整 + 全量回归 |
| JDK 21 → 25 LTS | 无强依赖；虚拟线程语义不变 | 小 |
| Spring AI 1.1.8 → 2.0 | `ReplayChatModel` 是唯一 touch 面（core）+ `SpringAiChatGateway`（starter） | 小-中 |

约定：升级 PR 必须更新本 ADR 的「状态」与验收记录，且 README 支持状态表同步修订。
