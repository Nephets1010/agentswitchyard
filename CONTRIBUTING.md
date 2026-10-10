# 贡献指南

感谢关注 agentswitchyard！本仓的定位一句话：**我们做 LangGraph4j 的可靠性层，不替代它。** 提案与实现请守住这条边界。

## 开发环境

- JDK 21（LTS）
- Maven（仓内自带 `./mvnw`）
- Docker（可选：Testcontainers 的 MySQL / PostgreSQL IT；无 Docker 时自动跳过）
- 提交前配置 git：`.gitattributes` 已锁定 `mvnw` 为 LF

## 常用命令

```bash
./mvnw -q clean verify          # 全量测试 + 覆盖率门槛（core ≥ 80%，全仓 ≥ 60%）
./mvnw -B spotless:check        # 代码风格（google-java-format AOSP）
./mvnw -pl sample spring-boot:run   # 本地跑信贷 demo
python tools/validate_workflows.py  # CI workflow YAML 严格校验
```

## 质量标准（CI 与本地同一标准）

1. `mvn clean verify` 全绿，含真实 kill -9 崩溃恢复 IT 与四方言 IT；
2. `spotless:check` 通过（不要手动格式化，跑 `spotless:apply`）；
3. **CI 全程零真实 API 调用、零费用** —— 模型相关测试一律走 mock-llm 或 WireMock；
4. 新增 SPI 必须有纯 POJO 内存实现（core 可单测），参考 `InMemoryStepStore`；
5. 动「幂等 / 审批 CAS / 补偿」的行为，必须同步加强对应并发测试；
6. 中文注释、英文标识符；文档双语（README 优先）。

## 提交 PR

1. Fork + 分支（`feat/xxx` / `fix/xxx`）；
2. PR 描述说明「为什么」而不只是「改了什么」；
3. 涉及契约（contract-v1.json）的改动必须同步更新契约测试与 CHANGELOG；
4. 一个 PR 一件事，不做顺手重构。

## 提 Issue

- Bug：附最小复现（流程 YAML + handler + 期望/实际）；
- 能力提案：先说明它属于「可靠性层」而不是「编排引擎」（ADR-0002）。
