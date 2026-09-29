package org.javaboy.backend.config;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.mysql.MysqlDistributedStore;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import org.javaboy.backend.agent.LogQueryTool;
import org.javaboy.backend.agent.ModelCatalog;
import org.javaboy.backend.agent.ModelRetryMiddleware;
import org.javaboy.backend.agent.ModelRoutingMiddleware;
import org.javaboy.backend.sandbox.AoneSandboxFilesystem;
import org.javaboy.backend.sandbox.AoneSandboxRegistry;
import org.javaboy.backend.skill.contextlab.ContextlabClient;
import org.javaboy.backend.skill.contextlab.ContextlabSkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.concurrent.Executor;

@Configuration
@EnableConfigurationProperties(SreAgentProperties.class)
public class AgentScopeConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeConfig.class);

    @Bean
    public ModelCatalog modelCatalog(SreAgentProperties properties) {
        return new ModelCatalog(properties);
    }

    @Bean
    public ModelRoutingMiddleware modelRoutingMiddleware(ModelCatalog modelCatalog) {
        return new ModelRoutingMiddleware(modelCatalog);
    }

    @Bean
    public ModelRetryMiddleware modelRetryMiddleware(SreAgentProperties properties) {
        return new ModelRetryMiddleware(
                properties.getModelRetry().isEnabled(),
                properties.getModelRetry().getMaxRetries());
    }

    @Bean
    @ConditionalOnProperty(prefix = "sreagent.contextlab", name = "enabled", havingValue = "true")
    public ContextlabSkillRepository contextlabSkillRepository(
            ContextlabClient client, SreAgentProperties properties) {
        return new ContextlabSkillRepository(client, properties);
    }

    @Bean
    public DistributedStore agentDistributedStore(DataSource dataSource, SreAgentProperties properties) {
        // MysqlDistributedStore 的默认 agentStateStore() 会把会话状态写入硬编码的 "agentscope" 库，
        // 这里包一层，让状态存储与业务数据一样落在 sre_agent 库中。
        MysqlDistributedStore delegate = MysqlDistributedStore.create(dataSource);
        AgentStateStore stateStore = new MysqlAgentStateStore(
                dataSource,
                properties.getAgentStateDatabase(),
                properties.getAgentStateTable(),
                true);
        return new DistributedStore() {
            @Override
            public AgentStateStore agentStateStore() {
                return stateStore;
            }

            @Override
            public BaseStore baseStore() {
                return delegate.baseStore();
            }

            @Override
            public SandboxSnapshotSpec sandboxSnapshotSpec() {
                return delegate.sandboxSnapshotSpec();
            }

            @Override
            public SandboxExecutionGuard sandboxExecutionGuard() {
                return delegate.sandboxExecutionGuard();
            }
        };
    }

    @Bean(destroyMethod = "close")
    public HarnessAgent sreHarnessAgent(
            SreAgentProperties properties,
            ModelCatalog modelCatalog,
            ModelRoutingMiddleware modelRoutingMiddleware,
            ModelRetryMiddleware modelRetryMiddleware,
            LogQueryTool logQueryTool,
            AoneSandboxRegistry aoneSandboxRegistry,
            DistributedStore agentDistributedStore,
            Optional<ContextlabSkillRepository> contextlabSkillRepository) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(logQueryTool);

        SreAgentProperties.Sandbox sandbox = properties.getSandbox();
        boolean sandboxEnabled = sandbox.isEnabled()
                && sandbox.getApiKey() != null
                && !sandbox.getApiKey().isBlank();
        String systemPrompt = "你是百炼推理服务的 SRE 诊断助手。信息不足时明确指出，不得编造指标、日志和根因。";

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(properties.getName())
                .description("百炼推理服务 SRE 诊断助手")
                .sysPrompt(systemPrompt)
                .model(modelCatalog.defaultModel())
                .middleware(modelRoutingMiddleware)
                .middleware(modelRetryMiddleware)
                .toolkit(toolkit)
                .workspace(properties.getWorkspace())
                .distributedStore(agentDistributedStore)
                .compaction(CompactionConfig.builder()
                        .triggerMessages(properties.getCompactionTriggerMessages())
                        .keepMessages(properties.getCompactionKeepMessages())
                        .build())
                .maxIters(properties.getMaxIters())
                .disableSubagents();

        contextlabSkillRepository.ifPresent(builder::skillRepository);

        if (sandboxEnabled) {
            builder.abstractFilesystem(new AoneSandboxFilesystem(aoneSandboxRegistry, properties));
            log.info("Aone 用户级共享沙箱文件系统已启用");
        } else {
            // 未配置沙箱时不暴露任何文件或 shell 工具，避免回退到其它文件系统。
            builder.disableFilesystemTools().disableShellTool();
            if (sandbox.isEnabled()) {
                log.warn("已启用 Aone 沙箱，但未配置 AONE_SANDBOX_API_KEY，文件与 shell 工具不可用");
            }
        }

        // 大工具结果卸载：单次工具返回超过阈值时，全文落盘到 workspace 的 large_tool_results/ 目录，
        // 上下文里只留首尾预览 + 占位符，agent 需要全文时用 read_file 回读。与上下文压缩相互独立。
        if (properties.isToolResultEvictionEnabled()) {
            builder.toolResultEviction(ToolResultEvictionConfig.builder()
                    .maxResultChars(properties.getToolResultEvictionMaxChars())
                    .previewChars(properties.getToolResultEvictionPreviewChars())
                    .build());
        }

        return builder.build();
    }

    @Bean("diagnosisExecutor")
    public Executor diagnosisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("diagnosis-");
        executor.initialize();
        return executor;
    }

    /**
     * 聊天流式专用线程池。每条 SSE 会占用一个线程，以阻塞方式迭代 Agent 事件直到本轮结束，
     * 因此线程数决定了并发对话上限；队列不宜过大，避免请求在队列里长时间等不到线程。
     */
    @Bean("chatStreamExecutor")
    public Executor chatStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(64);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("chat-stream-");
        executor.initialize();
        return executor;
    }
}
