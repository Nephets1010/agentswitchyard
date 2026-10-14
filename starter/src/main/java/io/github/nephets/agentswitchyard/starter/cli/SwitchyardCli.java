/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.starter.cli;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * agentswitchyard CLI。
 *
 * <p>用法：{@code swy [--base-url URL] {run|approve|replay|doctor} ...}
 */
@Command(
        name = "swy",
        mixinStandardHelpOptions = true,
        version = "swy 0.1.0",
        description = "agentswitchyard 命令行：run / approve / replay / doctor",
        subcommands = {
            SwitchyardCli.RunCommand.class,
            SwitchyardCli.ApproveCommand.class,
            SwitchyardCli.ReplayCommand.class,
            SwitchyardCli.DoctorCommand.class
        })
public class SwitchyardCli implements Callable<Integer> {

    /** 引擎地址（默认本地 8080）。 */
    @Option(
            names = {"-b", "--base-url"},
            description = "引擎地址（默认 http://localhost:8080）",
            defaultValue = "http://localhost:8080")
    String baseUrl;

    /** 共享 HTTP 客户端。 */
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** 入口（仅当无子命令时打印帮助）。 */
    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    /** GET/POST 辅助：非 2xx 打印响应体并返回 null。 */
    String request(String method, String path, String jsonBody) throws Exception {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + path))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json");
        if (jsonBody == null) {
            builder.GET();
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        }
        HttpResponse<String> response =
                http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return response.body();
        }
        System.err.println("HTTP " + response.statusCode() + "：");
        System.err.println(response.body());
        return null;
    }

    /** run：启动流程。 */
    @Command(name = "run", description = "启动一个流程实例")
    static class RunCommand implements Callable<Integer> {

        @CommandLine.ParentCommand SwitchyardCli parent;

        @Option(
                names = {"-f", "--flow"},
                required = true,
                description = "流程 ID")
        String flowId;

        @Option(
                names = {"-i", "--input"},
                defaultValue = "{}",
                description = "流程输入 JSON")
        String input;

        @Option(
                names = {"--tenant"},
                defaultValue = "",
                description = "租户 ID（可选）")
        String tenant;

        @Override
        public Integer call() throws Exception {
            String body =
                    "{\"input\":"
                            + input
                            + ",\"tenantId\":"
                            + (tenant.isBlank() ? "null" : "\"" + tenant + "\"")
                            + "}";
            String response =
                    parent.request("POST", "/_switchyard/flows/" + flowId + "/start", body);
            if (response != null) {
                System.out.println(response);
                return 0;
            }
            return 1;
        }
    }

    /** approve：审批决策。 */
    @Command(name = "approve", description = "对审批提交决策（并发重复提交自动幂等）")
    static class ApproveCommand implements Callable<Integer> {

        @CommandLine.ParentCommand SwitchyardCli parent;

        @Parameters(index = "0", description = "审批 ID")
        String approvalId;

        @Option(
                names = {"--approver"},
                required = true,
                description = "审批人")
        String approver;

        @Option(
                names = {"--role"},
                required = true,
                description = "审批角色")
        String role;

        @Option(
                names = {"--approve", "-y"},
                description = "同意")
        boolean approve;

        @Option(
                names = {"--reject", "-n"},
                description = "拒绝")
        boolean reject;

        @Option(
                names = {"--note"},
                defaultValue = "",
                description = "审批意见")
        String note;

        @Override
        public Integer call() throws Exception {
            if (approve == reject) {
                System.err.println("必须且只能选择 --approve 或 --reject 之一");
                return 2;
            }
            String body =
                    "{\"approver\":\""
                            + approver
                            + "\",\"approverRole\":\""
                            + role
                            + "\",\"approved\":"
                            + approve
                            + ",\"note\":\""
                            + note
                            + "\",\"degraded\":false}";
            String response =
                    parent.request("POST", "/_switchyard/approval/" + approvalId + "/decide", body);
            if (response != null) {
                System.out.println(response);
                return 0;
            }
            return 1;
        }
    }

    /** replay：确定性回放。 */
    @Command(name = "replay", description = "对实例做确定性回放（零上游调用，返回差异）")
    static class ReplayCommand implements Callable<Integer> {

        @CommandLine.ParentCommand SwitchyardCli parent;

        @Parameters(index = "0", description = "流程实例 ID")
        String instanceId;

        @Override
        public Integer call() throws Exception {
            String response = parent.request("POST", "/_switchyard/replay/" + instanceId, "");
            if (response != null) {
                System.out.println(response);
                return 0;
            }
            return 1;
        }
    }

    /** doctor：引擎自检。 */
    @Command(name = "doctor", description = "引擎自检（注册的流程 / 工具 / 协议版本）")
    static class DoctorCommand implements Callable<Integer> {

        @CommandLine.ParentCommand SwitchyardCli parent;

        @Override
        public Integer call() throws Exception {
            String response = parent.request("GET", "/_switchyard/health", null);
            if (response != null) {
                System.out.println(response);
                return 0;
            }
            return 1;
        }
    }

    /**
     * CLI 入口（main）：{@code java -jar agentswitchyard-cli.jar run --flow credit-approve ...}。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        int exitCode = new CommandLine(new SwitchyardCli()).execute(args);
        System.exit(exitCode);
    }
}
