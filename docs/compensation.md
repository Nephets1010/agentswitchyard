# 补偿事务（Saga）

## 两条硬规则

1. **补偿失败不掩盖原始异常**：原始异常永远是主异常，补偿失败全部挂 `addSuppressed`，打进日志与报告（架构 §8.4）。运维看到的永远是「为什么失败」而不是「撤销也失败了」的噪音。
2. **某个补偿失败不中断其余补偿**：尽量多撤销，失败明细进 `CompensationReport`。

## 反向顺序

`CompensationChain.run(已成功步骤)` 按 `seq` **倒序**执行：最后做的事最先撤销 —— 转账类业务里这是唯一正确的顺序。报告 `entries` 顺序即执行顺序，可机器验证（`FlowExecutorTest.failureTriggersReverseCompensation` 断言 `containsExactly("undo2:s2", "undo1:s1")`）。

## 声明补偿

```yaml
- stepId: disburse
  kind: TOOL
  compensation: cancelReserve   # 指向 CompensationAction bean 名
```

```java
@Bean
public CompensationAction cancelReserve() {
    return executed -> ledger.markCancelled(executed.flowInstanceId(), "disburse")
            ? CompensationOutcome.ok("预留额度已释放")
            : CompensationOutcome.fail("撤销失败", "没有可撤销的放款记录");
}
```

自动配置把全部 `CompensationAction` bean 按 bean 名装配进补偿链；步骤 → actionRef 的解析由 FlowExecutor 按流程定义在运行期提供。

## 终态语义

```
步骤最终失败 → FAILED → COMPENSATING → COMPENSATED       （全部撤销成功）
                                    └→ COMPENSATE_FAILED  （部分撤销失败 —— 最严重终态，人工介入）
```

`COMPENSATE_FAILED` 不是「再试一次」能解决的，必须人工介入 —— 所以它是终态并触发告警（PRD 对补偿失败率的 1% 红线监控在此）。

## 与重试的关系

重试是步骤内的（`maxAttempts` + 退避，`ErrorClassifier` 区分可重试/不可重试）；补偿是流程级的。规则引擎的「不可重试拒绝」直接进入补偿路径（`FullFlowCompensationIT`）。
