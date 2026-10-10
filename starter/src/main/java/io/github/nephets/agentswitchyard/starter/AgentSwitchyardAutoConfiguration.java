/*
 * Copyright 2026 The agentswitchyard Authors. Licensed under the Apache License, Version 2.0 (the "License"); see the
 * LICENSE file in the project root for the full license text.
 */
package io.github.nephets.agentswitchyard.starter;

import io.github.nephets.agentswitchyard.core.approval.ApprovalGate;
import io.github.nephets.agentswitchyard.core.approval.ApprovalStore;
import io.github.nephets.agentswitchyard.core.approval.RoleApprovalAuthorizer;
import io.github.nephets.agentswitchyard.core.approval.WebhookApprovalChannel;
import io.github.nephets.agentswitchyard.core.chat.ChatGateway;
import io.github.nephets.agentswitchyard.core.compensation.CompensationAction;
import io.github.nephets.agentswitchyard.core.compensation.CompensationChain;
import io.github.nephets.agentswitchyard.core.definition.FlowDefinition;
import io.github.nephets.agentswitchyard.core.definition.StepHandler;
import io.github.nephets.agentswitchyard.core.definition.YamlFlowLoader;
import io.github.nephets.agentswitchyard.core.executor.FlowExecutor;
import io.github.nephets.agentswitchyard.core.replay.ReplayRecorder;
import io.github.nephets.agentswitchyard.core.retry.DefaultErrorClassifier;
import io.github.nephets.agentswitchyard.core.retry.ErrorClassifier;
import io.github.nephets.agentswitchyard.core.store.StepStore;
import io.github.nephets.agentswitchyard.core.tool.ToolRegistry;
import io.github.nephets.agentswitchyard.core.tool.ToolSandbox;
import io.github.nephets.agentswitchyard.store.Dialect;
import io.github.nephets.agentswitchyard.store.FlywayMigrator;
import io.github.nephets.agentswitchyard.store.JdbcApprovalStore;
import io.github.nephets.agentswitchyard.store.JdbcStepStore;
import io.github.nephets.agentswitchyard.store.TraceBlobStore;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * agentswitchyard 自动配置（唯一入口）。
 *
 * <p>装配顺序：DataSource（业务方提供）→ Flyway 迁移 → JDBC 存储实现 → 审批门 / 补偿链 → FlowExecutor（注册 YAML 流程）→
 * 启动时崩溃恢复（{@code recoverInterrupted}）。
 *
 * <p>业务扩展点：
 *
 * <ul>
 *   <li>{@link StepHandler} bean：bean 名 = stepId
 *   <li>{@link CompensationAction} bean：bean 名 = CompensationSpec.actionRef
 *   <li>流程定义：{@code agentswitchyard.flow-definitions: [classpath:flows/xxx.yaml]}
 * </ul>
 */
@AutoConfiguration
@ConditionalOnClass(FlowExecutor.class)
@EnableConfigurationProperties(AgentSwitchyardProperties.class)
public class AgentSwitchyardAutoConfiguration {

    // ==================== 持久化层 ====================

    /** 从数据源推断 SQL 方言（不识别的 URL fail-fast，防止幂等语义静默失效）。 */
    @Bean
    @ConditionalOnMissingBean(Dialect.class)
    public Dialect switchyardDialect(javax.sql.DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            return Dialect.fromUrl(connection.getMetaData().getURL());
        }
    }

    /** Flyway 迁移（V1 基线 → V2 唯一索引 → V3 审计列）。 */
    @Bean
    public int switchyardSchemaMigrator(javax.sql.DataSource dataSource) {
        return FlywayMigrator.migrate(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean(JdbcTemplate.class)
    public JdbcTemplate switchyardJdbcTemplate(javax.sql.DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean(StepStore.class)
    public JdbcStepStore switchyardStepStore(JdbcTemplate jdbcTemplate, Dialect dialect) {
        return new JdbcStepStore(jdbcTemplate, dialect);
    }

    @Bean
    @ConditionalOnMissingBean(ApprovalStore.class)
    public JdbcApprovalStore switchyardApprovalStore(JdbcTemplate jdbcTemplate, Dialect dialect) {
        return new JdbcApprovalStore(jdbcTemplate, dialect);
    }

    @Bean
    @ConditionalOnMissingBean(io.github.nephets.agentswitchyard.core.replay.ReplayStore.class)
    public TraceBlobStore switchyardTraceBlobStore(JdbcTemplate jdbcTemplate) {
        return new TraceBlobStore(jdbcTemplate);
    }

    // ==================== 审批 / 补偿 ====================

    @Bean
    @ConditionalOnMissingBean(RoleApprovalAuthorizer.class)
    public RoleApprovalAuthorizer switchyardApprovalAuthorizer(
            AgentSwitchyardProperties properties) {
        return new RoleApprovalAuthorizer(properties.rostersAsMap());
    }

    @Bean
    @ConditionalOnMissingBean
    public ApprovalGate switchyardApprovalGate(
            ApprovalStore approvalStore, RoleApprovalAuthorizer authorizer) {
        return new ApprovalGate(
                approvalStore, authorizer, new WebhookApprovalChannel(), Clock.systemUTC());
    }

    /**
     * 补偿链：动作注册表 = 全部 {@link CompensationAction} bean（bean 名 = actionRef）； 步骤 → actionRef 的解析经
     * StepStore + FlowExecutor 的流程定义（延迟到运行期，避免装配环）。
     */
    @Bean
    @ConditionalOnMissingBean
    public CompensationChain switchyardCompensationChain(
            ApplicationContext context,
            StepStore stepStore,
            ObjectProvider<FlowExecutor> flowExecutor) {
        Map<String, CompensationAction> actions = new HashMap<>();
        context.getBeansOfType(CompensationAction.class).forEach(actions::put);
        Function<io.github.nephets.agentswitchyard.core.instance.StepRecord, String> resolver =
                step -> {
                    FlowExecutor executor = flowExecutor.getIfAvailable();
                    if (executor == null) {
                        return null;
                    }
                    FlowDefinition definition =
                            executor.definition(
                                    stepStore.loadInstance(step.flowInstanceId()).flowId());
                    if (definition == null) {
                        return null;
                    }
                    return definition.step(step.stepId()).compensation().actionRef();
                };
        return new CompensationChain(actions, Clock.systemUTC(), resolver);
    }

    // ==================== 执行引擎 ====================

    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean
    public FlowExecutor flowExecutor(
            StepStore stepStore,
            ApprovalGate approvalGate,
            CompensationChain compensationChain,
            ObjectProvider<ChatGateway> chatGateway,
            ObjectProvider<ReplayRecorder> replayRecorder,
            ApplicationContext context,
            AgentSwitchyardProperties properties) {
        Map<String, StepHandler> handlers = new HashMap<>();
        context.getBeansOfType(StepHandler.class).forEach(handlers::put);
        FlowExecutor executor =
                new FlowExecutor(
                        stepStore,
                        handlers,
                        approvalGate,
                        compensationChain,
                        chatGateway.getIfAvailable(),
                        defaultClassifier(context),
                        Clock.systemUTC(),
                        replayRecorder.getIfAvailable());
        for (String location : properties.getFlowDefinitions()) {
            // 支持 Spring 风格的 classpath: 前缀；无前缀按 classpath 资源处理
            String resource =
                    location.startsWith("classpath:")
                            ? location.substring("classpath:".length())
                            : location;
            FlowDefinition definition = YamlFlowLoader.loadFromClasspath(resource);
            executor.register(definition);
        }
        return executor;
    }

    private ErrorClassifier defaultClassifier(ApplicationContext context) {
        var provider = context.getBeanProvider(ErrorClassifier.class);
        ErrorClassifier classifier = provider.getIfAvailable();
        return classifier == null ? new DefaultErrorClassifier() : classifier;
    }

    /** 启动时崩溃恢复：把 RUNNING 残留实例续跑（kill -9 后重启的入口，PRD P0-1）。 */
    @Bean
    public ApplicationRunner switchyardCrashRecoveryRunner(
            FlowExecutor flowExecutor, AgentSwitchyardProperties properties) {
        return args -> {
            if (!properties.getRecovery().isEnabled()) {
                return;
            }
            int recovered = flowExecutor.recoverInterrupted();
            org.slf4j.LoggerFactory.getLogger(AgentSwitchyardAutoConfiguration.class)
                    .info("崩溃恢复完成：续跑 {} 个被中断实例", recovered);
        };
    }

    // ==================== 工具治理 ====================

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry switchyardToolRegistry() {
        return new ToolRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolSandbox switchyardToolSandbox(ToolRegistry registry) {
        return new ToolSandbox(registry);
    }

    /** 扫描 bean 方法上的 {@link io.github.nephets.agentswitchyard.core.tool.SwitchyardTool} 注解并注册。 */
    @Bean
    public static BeanPostProcessor switchyardToolRegisteringPostProcessor(ToolRegistry registry) {
        return new ToolRegisteringPostProcessor(registry);
    }

    /** 健康检查（actuator /actuator/health 下出现 switchyard 项）。 */
    @Bean
    @ConditionalOnMissingBean(name = "switchyardHealthIndicator")
    public SwitchyardHealthIndicator switchyardHealthIndicator(
            FlowExecutor flowExecutor, ApprovalGate approvalGate) {
        return new SwitchyardHealthIndicator(flowExecutor, approvalGate);
    }

    // ==================== LLM 网关（有 ChatModel 时才装配） ====================

    @Bean
    @ConditionalOnBean(org.springframework.ai.chat.model.ChatModel.class)
    @ConditionalOnMissingBean(ChatGateway.class)
    public ChatGateway switchyardChatGateway(
            org.springframework.ai.chat.model.ChatModel chatModel,
            ObjectProvider<ReplayRecorder> replayRecorder) {
        return new SpringAiChatGateway(chatModel, replayRecorder.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public ReplayRecorder switchyardReplayRecorder(
            io.github.nephets.agentswitchyard.core.replay.ReplayStore replayStore) {
        return new ReplayRecorder(replayStore);
    }
}
