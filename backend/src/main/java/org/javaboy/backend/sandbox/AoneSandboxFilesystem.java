package org.javaboy.backend.sandbox;

import com.alibaba.aone.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.ExecutionError;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.ExecutionLogs;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.OutputMessage;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.sandbox.BaseSandboxFilesystem;
import org.javaboy.backend.config.SreAgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 基于 Aone 沙箱的 Harness 文件系统。
 *
 * <p>实例本身不保存当前沙箱，每次调用都根据 RuntimeContext.userId 从注册表取沙箱，
 * 因而同一个 HarnessAgent 可以安全地服务多个并发用户。
 */
public class AoneSandboxFilesystem extends BaseSandboxFilesystem {

    private static final Logger log = LoggerFactory.getLogger(AoneSandboxFilesystem.class);

    private final AoneSandboxRegistry registry;
    private final SreAgentProperties.Sandbox properties;

    public AoneSandboxFilesystem(AoneSandboxRegistry registry, SreAgentProperties properties) {
        this.registry = registry;
        this.properties = properties.getSandbox();
    }

    @Override
    public String id() {
        return "aone-user-sandbox";
    }

    @Override
    public ExecuteResponse execute(
            RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
        if (command == null || command.isBlank()) {
            return new ExecuteResponse("command 不能为空", -1, false);
        }
        if (!hasUserContext(runtimeContext)) {
            log.debug("启动阶段无用户上下文，跳过沙箱命令执行: {}", command);
            return new ExecuteResponse("", 1, false);
        }
        try {
            Sandbox sandbox = sandbox(runtimeContext);
            int configuredTimeout = positive(properties.getExecTimeoutSeconds(), 120);
            int timeout = timeoutSeconds == null
                    ? configuredTimeout
                    : Math.max(1, Math.min(timeoutSeconds, configuredTimeout));
            Execution execution = sandbox.commands().run(RunCommandRequest.builder()
                    .command(command)
                    .workingDirectory(registry.getWorkDir())
                    .timeout(Duration.ofSeconds(timeout))
                    .build());
            String output = formatExecution(execution);
            int limit = positive(properties.getMaxOutputChars(), 20_000);
            boolean truncated = output.length() > limit;
            if (truncated) {
                output = output.substring(0, limit)
                        + "\n...（输出已截断，共 " + output.length() + " 字符）";
            }
            Integer exitCode = execution.getExitCode();
            return new ExecuteResponse(output, exitCode == null ? -1 : exitCode, truncated);
        } catch (RuntimeException ex) {
            log.warn("Aone 沙箱命令执行失败: {}", safeMessage(ex));
            return new ExecuteResponse("Aone 沙箱执行失败：" + safeMessage(ex), -1, false);
        }
    }

    @Override
    public ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        try {
            byte[] content = sandbox(runtimeContext).files().readByteArray(resolvePath(filePath));
            String fileType = io.agentscope.harness.agent.filesystem.util.FilesystemUtils
                    .getFileType(filePath);
            if (!"text".equals(fileType)) {
                return ReadResult.success(
                        new FileData(Base64.getEncoder().encodeToString(content), "base64"));
            }
            if (content.length == 0) {
                return ReadResult.success(new FileData(
                        "System reminder: File exists but has empty contents", "utf-8"));
            }
            String text = new String(content, StandardCharsets.UTF_8);
            int effectiveLimit = limit > 0 ? limit : Integer.MAX_VALUE;
            String page = text.lines()
                    .skip(Math.max(0, offset))
                    .limit(effectiveLimit)
                    .collect(Collectors.joining("\n"));
            return ReadResult.success(new FileData(page, "utf-8"));
        } catch (RuntimeException ex) {
            log.debug("读取 Aone 沙箱文件失败，path={}: {}", filePath, safeMessage(ex));
            return ReadResult.fail("File '" + filePath + "': " + safeMessage(ex));
        }
    }

    @Override
    public EditResult edit(
            RuntimeContext runtimeContext,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll) {
        if (oldString == null || oldString.isEmpty()) {
            return EditResult.fail("Error: oldString must not be empty");
        }
        if (oldString.equals(newString)) {
            return EditResult.fail("Error: oldString and newString must be different");
        }
        try {
            Sandbox sandbox = sandbox(runtimeContext);
            String resolvedPath = resolvePath(filePath);
            String content = sandbox.files().readFile(resolvedPath);
            int occurrences = countOccurrences(content, oldString);
            if (occurrences == 0) {
                return EditResult.fail("Error: String not found in file: '" + oldString + "'");
            }
            if (occurrences > 1 && !replaceAll) {
                return EditResult.fail("Error: String '" + oldString
                        + "' appears multiple times. Use replaceAll=true to replace all occurrences.");
            }
            String updated;
            int replaced;
            if (replaceAll) {
                updated = content.replace(oldString, newString == null ? "" : newString);
                replaced = occurrences;
            } else {
                int index = content.indexOf(oldString);
                updated = content.substring(0, index)
                        + (newString == null ? "" : newString)
                        + content.substring(index + oldString.length());
                replaced = 1;
            }
            sandbox.files().writeFile(resolvedPath, updated);
            return EditResult.ok(filePath, replaced);
        } catch (RuntimeException ex) {
            log.warn("编辑 Aone 沙箱文件失败，path={}: {}", filePath, safeMessage(ex));
            return EditResult.fail("Error editing file '" + filePath + "': " + safeMessage(ex));
        }
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        List<FileUploadResponse> responses = new ArrayList<>();
        if (files == null) {
            return responses;
        }
        Sandbox sandbox;
        try {
            sandbox = sandbox(runtimeContext);
        } catch (RuntimeException ex) {
            for (Map.Entry<String, byte[]> file : files) {
                responses.add(FileUploadResponse.fail(file.getKey(), safeMessage(ex)));
            }
            return responses;
        }

        for (Map.Entry<String, byte[]> file : files) {
            String requestedPath = file.getKey();
            try {
                String resolvedPath = resolvePath(requestedPath);
                Path parent = Path.of(resolvedPath).getParent();
                if (parent != null) {
                    sandbox.files().createDirectories(List.of(WriteEntry.builder()
                            .path(parent.toString())
                            .mode(755)
                            .build()));
                }
                sandbox.files().writeFile(
                        resolvedPath, file.getValue() == null ? new byte[0] : file.getValue());
                responses.add(FileUploadResponse.success(requestedPath));
            } catch (RuntimeException ex) {
                log.warn("上传文件到 Aone 沙箱失败，path={}: {}", requestedPath, safeMessage(ex));
                responses.add(FileUploadResponse.fail(requestedPath, safeMessage(ex)));
            }
        }
        return responses;
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths) {
        List<FileDownloadResponse> responses = new ArrayList<>();
        if (paths == null) {
            return responses;
        }
        Sandbox sandbox;
        try {
            sandbox = sandbox(runtimeContext);
        } catch (RuntimeException ex) {
            for (String path : paths) {
                responses.add(FileDownloadResponse.fail(path, safeMessage(ex)));
            }
            return responses;
        }

        for (String path : paths) {
            try {
                byte[] content = sandbox.files().readByteArray(resolvePath(path));
                responses.add(FileDownloadResponse.success(path, content));
            } catch (RuntimeException ex) {
                log.warn("从 Aone 沙箱下载文件失败，path={}: {}", path, safeMessage(ex));
                responses.add(FileDownloadResponse.fail(path, safeMessage(ex)));
            }
        }
        return responses;
    }

    private Sandbox sandbox(RuntimeContext runtimeContext) {
        if (!hasUserContext(runtimeContext)) {
            throw new IllegalArgumentException("无法识别当前用户，不能访问沙箱文件系统");
        }
        return registry.getOrCreate(runtimeContext.getUserId());
    }

    private boolean hasUserContext(RuntimeContext runtimeContext) {
        return runtimeContext != null
                && runtimeContext.getUserId() != null
                && !runtimeContext.getUserId().isBlank();
    }

    private String resolvePath(String path) {
        if (path == null || path.isBlank() || ".".equals(path.trim())) {
            return registry.getWorkDir();
        }
        Path requested = Path.of(path.trim());
        if (requested.isAbsolute()) {
            return requested.normalize().toString();
        }
        Path workDir = Path.of(registry.getWorkDir()).normalize();
        Path resolved = workDir.resolve(requested).normalize();
        if (!resolved.startsWith(workDir)) {
            throw new IllegalArgumentException("路径不能越过沙箱工作目录: " + path);
        }
        return resolved.toString();
    }

    private String formatExecution(Execution execution) {
        StringBuilder output = new StringBuilder();
        ExecutionLogs logs = execution.getLogs();
        appendMessages(output, logs == null ? null : logs.getStdout());
        appendMessages(output, logs == null ? null : logs.getStderr());
        ExecutionError error = execution.getError();
        if (error != null) {
            output.append(error.getName()).append(": ").append(error.getValue()).append('\n');
            if (error.getTraceback() != null && !error.getTraceback().isEmpty()) {
                output.append(String.join("\n", error.getTraceback())).append('\n');
            }
        }
        return output.toString();
    }

    private void appendMessages(StringBuilder output, List<OutputMessage> messages) {
        if (messages == null) {
            return;
        }
        for (OutputMessage message : messages) {
            if (message != null && message.getText() != null) {
                output.append(message.getText());
                if (!message.getText().endsWith("\n")) {
                    output.append('\n');
                }
            }
        }
    }

    private int countOccurrences(String content, String target) {
        int count = 0;
        int fromIndex = 0;
        while ((fromIndex = content.indexOf(target, fromIndex)) >= 0) {
            count++;
            fromIndex += target.length();
        }
        return count;
    }

    private int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }
}
