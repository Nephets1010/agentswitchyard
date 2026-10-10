/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.starter.cli;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

import picocli.CommandLine;

/** CLI 端到端（进程内 HttpServer 扮演引擎）：run / approve / replay / doctor 全走真实 HTTP。 */
@DisplayName("SwitchyardCli（HTTP 端到端）")
class SwitchyardCliHttpTest {

    private HttpServer server;
    private int port;
    private SwitchyardCli cli;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext(
                "/_switchyard/flows/demo-flow/start",
                exchange ->
                        respond(
                                exchange,
                                202,
                                "{\"flowInstanceId\":\"inst-1\",\"state\":\"PENDING\"}"));
        server.createContext(
                "/_switchyard/approval/appr-1/decide",
                exchange ->
                        respond(
                                exchange,
                                200,
                                "{\"approvalId\":\"appr-1\",\"applied\":true,\"state\":\"APPROVED\"}"));
        server.createContext(
                "/_switchyard/replay/inst-1",
                exchange -> respond(exchange, 200, "{\"identical\":true,\"diffs\":[]}"));
        server.createContext(
                "/_switchyard/health",
                exchange ->
                        respond(
                                exchange,
                                200,
                                "{\"status\":\"UP\",\"registeredFlows\":[\"demo-flow\"]}"));
        server.start();
        cli = new SwitchyardCli();
        cli.baseUrl = "http://127.0.0.1:" + port;
    }

    private static void respond(
            com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private int execute(Object command, String... args) {
        StringWriter out = new StringWriter();
        return new CommandLine(command).setOut(new PrintWriter(out)).execute(args);
    }

    @Test
    @DisplayName("run：POST start → 202，打印实例 ID")
    void run() {
        SwitchyardCli.RunCommand run = new SwitchyardCli.RunCommand();
        run.parent = cli;
        int exit = execute(run, "--flow", "demo-flow", "--input", "{\"ref\":\"R-1\"}");
        assertThat(exit).isZero();
    }

    @Test
    @DisplayName("approve --approve：POST decide → applied=true")
    void approve() {
        SwitchyardCli.ApproveCommand approve = new SwitchyardCli.ApproveCommand();
        approve.parent = cli;
        int exit =
                execute(
                        approve,
                        "appr-1",
                        "--approver",
                        "zhang-san",
                        "--role",
                        "risk-officer",
                        "-y",
                        "--note",
                        "同意");
        assertThat(exit).isZero();
    }

    @Test
    @DisplayName("replay：POST replay → identical=true")
    void replay() {
        SwitchyardCli.ReplayCommand replay = new SwitchyardCli.ReplayCommand();
        replay.parent = cli;
        assertThat(execute(replay, "inst-1")).isZero();
    }

    @Test
    @DisplayName("doctor：GET health → UP")
    void doctor() {
        SwitchyardCli.DoctorCommand doctor = new SwitchyardCli.DoctorCommand();
        doctor.parent = cli;
        assertThat(execute(doctor)).isZero();
    }

    @Test
    @DisplayName("引擎不可达：非 2xx → 退出码非零（错误可观测）")
    void unreachableEngineFailsGracefully() {
        cli.baseUrl = "http://127.0.0.1:1"; // 没有服务监听
        SwitchyardCli.DoctorCommand doctor = new SwitchyardCli.DoctorCommand();
        doctor.parent = cli;
        assertThat(execute(doctor)).isNotZero();
    }
}
