/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.sample.compensation;

import io.github.nephets.agentswitchyard.core.compensation.CompensationAction;
import io.github.nephets.agentswitchyard.core.compensation.CompensationOutcome;
import io.github.nephets.agentswitchyard.sample.ledger.Ledger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 补偿动作（bean 名 = CompensationSpec.actionRef，自动配置按名装配进补偿链）。 */
@Configuration
public class DemoCompensations {

    private final Ledger ledger;

    /** 创建补偿动作集合。 */
    public DemoCompensations(Ledger ledger) {
        this.ledger = ledger;
    }

    /** cancelReserve：撤销放款预留（credit-approve.yaml 里 disburse 步骤声明的补偿）。 */
    @Bean
    public CompensationAction cancelReserve() {
        return executed -> {
            boolean marked = ledger.markCancelled(executed.flowInstanceId(), "disburse");
            return marked
                    ? CompensationOutcome.ok("预留额度已释放（副作用台账已标记撤销）")
                    : CompensationOutcome.fail("撤销失败", "副作用台账中没有可撤销的放款记录");
        };
    }

    /** rollbackRiskSnapshot：回滚风险评分快照（演示多级反向补偿）。 */
    @Bean
    public CompensationAction rollbackRiskSnapshot() {
        return executed -> CompensationOutcome.ok("风险评分快照已回滚 step=" + executed.stepId());
    }
}
