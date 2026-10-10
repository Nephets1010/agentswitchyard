<!-- 提交前自检：mvn -B spotless:apply && ./mvnw clean verify -->

## 变更说明

<!-- 做了什么、为什么 -->

## 变更类型

- [ ] feat（新能力）
- [ ] fix（缺陷修复）
- [ ] docs（文档）
- [ ] refactor / test / chore / ci

## 自检清单

- [ ] `./mvnw clean verify` 通过（含覆盖率门禁与 CrashResumeIT）
- [ ] `mvn -B spotless:check` 通过
- [ ] 修改了契约（contract/contract-v1.json）→ 已同步另两个仓库（三仓同 PR）
- [ ] 新增/变更了对外 API（api 包签名）→ 已升 MINOR 并在 CHANGELOG 写迁移说明
- [ ] 未引入 LangGraph4j / Flowable / Camunda / LangChain4j（ADR-0002 / ADR-0003）
