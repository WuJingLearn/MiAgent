package org.javaboy.backend.sandbox;

import com.alibaba.aone.sandbox.Sandbox;
import io.agentscope.core.agent.RuntimeContext;
import org.javaboy.backend.config.SreAgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Aone 沙箱真实链路测试。默认跳过，只有显式设置 AONE_SANDBOX_LIVE_TEST=true 才会占用沙箱配额。
 */
@SpringBootTest(properties = "sreagent.sandbox.enabled=true")
@EnabledIfEnvironmentVariable(named = "AONE_SANDBOX_LIVE_TEST", matches = "true")
class AoneSandboxLiveTests {

    private static final String USER_ID = "aone-sandbox-live-test";

    @Autowired
    private AoneSandboxRegistry registry;

    @Autowired
    private SreAgentProperties properties;

    @Test
    void shouldReuseUserSandboxAndExecuteNativeFilesystemOperations() {
        Sandbox first = registry.getOrCreate(USER_ID);
        Sandbox second = registry.getOrCreate(USER_ID);
        assertSame(first, second);

        RuntimeContext context = RuntimeContext.builder()
                .userId(USER_ID)
                .sessionId(UUID.randomUUID().toString())
                .build();
        AoneSandboxFilesystem filesystem = new AoneSandboxFilesystem(registry, properties);
        String path = "live/hello-" + UUID.randomUUID() + ".txt";
        String content = "hello from SreAgent Aone sandbox";

        assertThat(filesystem.read(context, "AGENTS.md", 0, 100).fileData().content())
                .contains("百炼 SRE Agent");
        assertThat(filesystem.read(context, "MEMORY.md", 0, 100).isSuccess()).isTrue();
        assertThat(filesystem.read(context, "knowledge/KNOWLEDGE.md", 0, 100).isSuccess())
                .isTrue();
        assertThat(filesystem.write(context, path, content).isSuccess()).isTrue();
        assertThat(filesystem.read(context, path, 0, 100).fileData().content()).isEqualTo(content);
        assertThat(filesystem.edit(context, path, "hello", "hi", false).error()).isNull();
        assertThat(filesystem.execute(context, "cat " + path, 30))
                .satisfies(result -> {
                    assertThat(result.exitCode()).isZero();
                    assertThat(result.output()).contains("hi from SreAgent Aone sandbox");
                });
    }
}
