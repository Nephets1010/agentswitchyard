# 企业审批（Human-in-the-loop）

## 设计立场

审批挂起是**持久化**的一等公民，不是内存里的 `park()`：

- 进程重启后 `listPending` 仍能找到它，流程实例仍是 `WAITING_APPROVAL`；
- kill -9 也一样 —— 挂起状态在 `step_record` / `approval_record` 里，不在内存里；
- 决策有**角色花名册**、**意见留痕**（谁、何时、以什么身份、基于什么理由、批了还是拒了）。

## 四件事的唯一入口：ApprovalGate

| 能力 | 方法 | 语义 |
|------|------|------|
| 挂起 | `suspend(request)` | 建审批记录（UUID）+ 推待办，返回 approvalId |
| 人工决策 | `decide(approvalId, decision)` | 角色校验 → **CAS** 写决策 → 生效者回调 FlowExecutor 续跑 |
| 超时策略 | `applyTimeoutPolicy(approvalId)` | AUTO_APPROVE / AUTO_REJECT / AUTO_DEGRADE / HOLD_FOREVER（可注入 Clock） |
| 待办 | `listPending(role)` | 按角色过滤的持久化待办 |

## 并发决策只生效一次

`decide` 内部是对 `approval_record` 的**单条条件 UPDATE**（`WHERE state = 'PENDING'`）。两个审批人同时点「同意」，数据库行锁保证只有第一个生效；HTTP 响应里的 `applied` 字段如实告知。这正是「并发审批只生效一次」硬验收的实现依据（`ApprovalWebhookIT` 用并发双决策验证）。

## 角色授权

`RoleApprovalAuthorizer` 两层校验：

1. 审批人声称的角色必须与审批要求的角色完全一致；
2. （可选）配置了花名册时，审批人必须在名册内：

```yaml
agentswitchyard:
  approval:
    rosters:
      risk-officer: [zhang-san, li-si]
```

不通过 → `SW_APPROVAL_DENIED`（HTTP 403），审批保持 PENDING。

## 声明审批步骤

```yaml
- stepId: manual-review
  kind: HUMAN
  requiresApproval: true
  approvalRole: risk-officer
  idempotencyKey: "credit:{ref}"
```

YAML 加载期即校验（`requiresApproval=true` 必须给 `approvalRole`）—— 不允许「跑起来才发现第 4 步没配审批角色」。

## Webhook 入口

```
GET  /_switchyard/approval/pending?role=risk-officer
POST /_switchyard/approval/{approvalId}/decide
     {"approver":"zhang-san","approverRole":"risk-officer","approved":true,"note":"同意","degraded":false}
```

CLI：`swy approve <approvalId> --approver zhang-san --role risk-officer -y --note "同意"`
