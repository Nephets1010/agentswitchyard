/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.core.approval;

import io.github.nephets.agentswitchyard.core.ErrorCode;
import io.github.nephets.agentswitchyard.core.SwitchyardException;

import java.util.Map;
import java.util.Set;

/**
 * 默认授权器：审批人声称的角色必须与审批要求的角色完全一致。
 *
 * <p>增强模式：构造时传入 {@code role -> 可审批人集合} 的映射，则还要求审批人在该角色的 花名册里（企业既有权限体系经 starter 的配置接入，不强依赖 Spring
 * Security）。
 */
public final class RoleApprovalAuthorizer implements ApprovalAuthorizer {

    private final Map<String, Set<String>> rosters;

    /** 创建仅校验角色一致的授权器。 */
    public RoleApprovalAuthorizer() {
        this(Map.of());
    }

    /**
     * 创建带花名册的授权器。
     *
     * @param rosters 角色 → 可审批人集合；空 Map 表示只校验角色一致
     */
    public RoleApprovalAuthorizer(Map<String, Set<String>> rosters) {
        this.rosters = rosters == null ? Map.of() : Map.copyOf(rosters);
    }

    @Override
    public void authorize(ApprovalRecord record, ApprovalDecision decision) {
        if (!record.approverRole().equals(decision.approverRole())) {
            throw new SwitchyardException(
                    ErrorCode.SW_APPROVAL_DENIED,
                    "审批角色不符：本步骤要求角色 ["
                            + record.approverRole()
                            + "]，审批人 "
                            + decision.approver()
                            + " 声称角色 ["
                            + decision.approverRole()
                            + "]",
                    null);
        }
        Set<String> roster = rosters.get(record.approverRole());
        if (roster != null && !roster.isEmpty() && !roster.contains(decision.approver())) {
            throw new SwitchyardException(
                    ErrorCode.SW_APPROVAL_DENIED,
                    "审批人 "
                            + decision.approver()
                            + " 不在角色 ["
                            + record.approverRole()
                            + "] 的花名册中，拒绝审批",
                    null);
        }
    }
}
