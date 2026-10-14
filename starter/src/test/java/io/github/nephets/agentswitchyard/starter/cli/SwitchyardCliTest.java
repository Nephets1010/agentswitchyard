/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.starter.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

import picocli.CommandLine;

/** CLI：帮助文本与参数校验（不依赖真实引擎；HTTP 行为由端到端 IT 覆盖）。 */
@DisplayName("SwitchyardCli（picocli）")
class SwitchyardCliTest {

    @Test
    @DisplayName("--help 列出四个子命令")
    void helpListsSubcommands() {
        StringWriter out = new StringWriter();
        int exit =
                new CommandLine(new SwitchyardCli()).setOut(new PrintWriter(out)).execute("--help");
        assertThat(exit).isZero();
        assertThat(out.toString())
                .contains("run")
                .contains("approve")
                .contains("replay")
                .contains("doctor");
    }

    @Test
    @DisplayName("approve 必须显式选择 --approve 或 --reject 之一")
    void approveRequiresExplicitDecision() {
        SwitchyardCli cli = new SwitchyardCli();
        SwitchyardCli.ApproveCommand approve = new SwitchyardCli.ApproveCommand();
        approve.parent = cli;
        int exit =
                new CommandLine(approve)
                        .execute("appr-1", "--approver", "zhang-san", "--role", "risk-officer");
        assertThat(exit).isEqualTo(2);
    }

    @Test
    @DisplayName("run 缺少 --flow 报参数错误")
    void runRequiresFlow() {
        StringWriter err = new StringWriter();
        SwitchyardCli cli = new SwitchyardCli();
        SwitchyardCli.RunCommand run = new SwitchyardCli.RunCommand();
        run.parent = cli;
        int exit = new CommandLine(run).setErr(new PrintWriter(err)).execute();
        assertThat(exit).isNotZero();
    }
}
