package org.javaboy.backend.sandbox;

import com.alibaba.aone.sandbox.Sandbox;
import com.alibaba.aone.sandbox.config.ConnectionConfig;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest;
import java.time.Duration;
import java.util.List;

public class QuickStartExample {

    public static void main(String[] args) {
        // API Key 只从环境变量读取，避免凭据进入源码和版本历史。
        String apiKey = System.getenv("AONE_SANDBOX_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("请先设置 AONE_SANDBOX_API_KEY");
        }
        ConnectionConfig config = ConnectionConfig.builder()
                .apiKey(apiKey)
                .build();

        // 1. 创建 Sandbox 实例
        try (Sandbox sandbox = Sandbox.builder()
                .template("code-interpreter")
                .timeout(Duration.ofMinutes(10))
                .connectionConfig(config)
                .create()) {
            // 2. 写入文件
            sandbox.files().write(List.of(
                    WriteEntry.builder()
                            .path("/tmp/hello.txt")
                            .data("Hello, Aone Sandbox!\n")
                            .mode(644)
                            .build()
            ));

            // 3. 读取文件
            String content = sandbox.files().readFile("/tmp/hello.txt");
            System.out.println("文件内容:");
            System.out.println(content);

            // 4. 执行命令
            Execution execution = sandbox.commands().run(
                    RunCommandRequest.builder()
                            .command("cat /tmp/hello.txt")
                            .timeout(Duration.ofSeconds(300000))
                            .build()
            );

            System.out.println("命令输出:");
            System.out.println(execution.getLogs());

            // try-with-resources 只关闭本地连接，不主动 kill 远端沙箱。
        }
    }
}