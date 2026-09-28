package org.javaboy.backend.sandbox;

import com.alibaba.aone.sandbox.Sandbox;
import com.alibaba.aone.sandbox.config.ConnectionConfig;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxRenewResponse;
import jakarta.annotation.PreDestroy;
import org.javaboy.backend.config.SreAgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按用户复用 Aone 沙箱。
 *
 * <p>Redis 保存 userId 到 sandboxId 的映射，并用一把短期全局锁保护“检查配额 + 创建 + 登记”。
 * 锁不覆盖命令执行，同一用户的所有会话都连接到同一个远端沙箱。
 */
@Component
public class AoneSandboxRegistry {

    private static final Logger log = LoggerFactory.getLogger(AoneSandboxRegistry.class);
    private static final List<String> WORKSPACE_TEMPLATE_FILES =
            List.of("AGENTS.md", "tools.json", "knowledge/KNOWLEDGE.md");
    private static final RedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);
    private static final RedisScript<Long> DELETE_MAPPING_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final SreAgentProperties.Sandbox properties;
    private final Path workspaceTemplate;
    private final Map<String, SandboxEntry> localSandboxes = new ConcurrentHashMap<>();
    private final Map<String, Object> userLocks = new ConcurrentHashMap<>();

    public AoneSandboxRegistry(StringRedisTemplate redisTemplate, SreAgentProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties.getSandbox();
        this.workspaceTemplate = properties.getWorkspace().toAbsolutePath().normalize();
    }

    public Sandbox getOrCreate(String userId) {
        validateUserId(userId);
        validateConfiguration();

        Object userLock = userLocks.computeIfAbsent(userId, ignored -> new Object());
        synchronized (userLock) {
            SandboxEntry local = localSandboxes.get(userId);
            if (local != null && !isExpired(local.expiresAt())) {
                return renewIfNeeded(userId, local);
            }
            closeAndRemoveLocal(userId, local);

            Sandbox connected = connectPersisted(userId);
            if (connected != null) {
                return connected;
            }

            String ownerToken = UUID.randomUUID().toString();
            acquireCreateLock(ownerToken);
            try {
                // 其它实例可能已经在本实例等待创建锁期间完成了登记。
                connected = connectPersisted(userId);
                if (connected != null) {
                    return connected;
                }
                return createAndRegister(userId);
            } finally {
                redisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(createLockKey()), ownerToken);
            }
        }
    }

    public String getWorkDir() {
        String configured = properties.getWorkDir();
        return configured == null || configured.isBlank() ? "/workspace" : configured.trim();
    }

    private Sandbox connectPersisted(String userId) {
        String mappingKey = userMappingKey(userId);
        String sandboxId = redisTemplate.opsForValue().get(mappingKey);
        if (sandboxId == null || sandboxId.isBlank()) {
            return null;
        }

        Sandbox sandbox = null;
        try {
            sandbox = Sandbox.connector()
                    .sandboxId(sandboxId)
                    .connectionConfig(connectionConfig())
                    .connectTimeout(Duration.ofSeconds(positive(properties.getConnectTimeoutSeconds(), 30)))
                    .connect();
            OffsetDateTime expiresAt = sandbox.getInfo().getExpiresAt();
            if (expiresAt == null) {
                expiresAt = OffsetDateTime.now(ZoneOffset.UTC)
                        .plusMinutes(positive(properties.getTimeoutMinutes(), 60));
            }
            initializeWorkspace(sandbox);
            SandboxEntry entry = new SandboxEntry(sandbox, expiresAt);
            localSandboxes.put(userId, entry);
            persistMapping(userId, sandboxId, expiresAt);
            return renewIfNeeded(userId, entry);
        } catch (RuntimeException ex) {
            closeQuietly(sandbox);
            redisTemplate.execute(DELETE_MAPPING_SCRIPT, List.of(mappingKey), sandboxId);
            redisTemplate.opsForZSet().remove(activeSandboxesKey(), sandboxId);
            log.warn("无法连接用户 {} 的 Aone 沙箱 {}，将重新创建: {}", userId, sandboxId, ex.getMessage());
            return null;
        }
    }

    private Sandbox createAndRegister(String userId) {
        long now = System.currentTimeMillis();
        redisTemplate.opsForZSet().removeRangeByScore(activeSandboxesKey(), 0, now);
        Long activeCount = redisTemplate.opsForZSet().zCard(activeSandboxesKey());
        int maxSandboxes = positive(properties.getMaxSandboxes(), 5);
        if (activeCount != null && activeCount >= maxSandboxes) {
            throw new IllegalStateException(
                    "Aone 沙箱配额已满（" + activeCount + "/" + maxSandboxes + "），请等待已有沙箱过期");
        }

        Sandbox sandbox = null;
        try {
            Duration lifetime = Duration.ofMinutes(positive(properties.getTimeoutMinutes(), 60));
            sandbox = Sandbox.builder()
                    .template(nonBlank(properties.getTemplate(), "code-interpreter"))
                    .timeout(lifetime)
                    .readyTimeout(Duration.ofSeconds(positive(properties.getReadyTimeoutSeconds(), 120)))
                    .metadata("sreAgentUserId", userId)
                    .connectionConfig(connectionConfig())
                    .create();
            sandbox.files().createDirectories(List.of(WriteEntry.builder()
                    .path(getWorkDir())
                    .mode(755)
                    .build()));
            initializeWorkspace(sandbox);

            OffsetDateTime expiresAt = sandbox.getInfo().getExpiresAt();
            if (expiresAt == null) {
                expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plus(lifetime);
            }
            SandboxEntry entry = new SandboxEntry(sandbox, expiresAt);
            localSandboxes.put(userId, entry);
            persistMapping(userId, sandbox.getId(), expiresAt);
            log.info("已为用户 {} 创建 Aone 沙箱 {}，到期时间 {}", userId, sandbox.getId(), expiresAt);
            return sandbox;
        } catch (RuntimeException ex) {
            closeQuietly(sandbox);
            throw new IllegalStateException("创建 Aone 沙箱失败: " + safeMessage(ex), ex);
        }
    }

    private void initializeWorkspace(Sandbox sandbox) {
        for (String relativePath : WORKSPACE_TEMPLATE_FILES) {
            Path source = workspaceTemplate.resolve(relativePath).normalize();
            if (!source.startsWith(workspaceTemplate) || !Files.isRegularFile(source)) {
                log.debug("沙箱工作区模板文件不存在，跳过初始化: {}", source);
                continue;
            }
            try {
                writeIfMissing(sandbox, relativePath, Files.readAllBytes(source));
            } catch (IOException | RuntimeException ex) {
                log.warn("初始化沙箱工作区文件 {} 失败: {}", relativePath, safeMessage(ex));
            }
        }
        try {
            writeIfMissing(
                    sandbox,
                    "MEMORY.md",
                    "# Memory\n".getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException ex) {
            log.warn("初始化沙箱工作区文件 MEMORY.md 失败: {}", safeMessage(ex));
        }
    }

    private void writeIfMissing(Sandbox sandbox, String relativePath, byte[] content) {
        Path workDir = Path.of(getWorkDir()).normalize();
        Path target = workDir.resolve(relativePath).normalize();
        if (!target.startsWith(workDir)) {
            throw new IllegalArgumentException("工作区模板路径越界: " + relativePath);
        }
        Execution existenceCheck = sandbox.commands().run(RunCommandRequest.builder()
                .command("test -e " + shellQuote(target.toString()))
                .workingDirectory(getWorkDir())
                .timeout(Duration.ofSeconds(10))
                .build());
        if (existenceCheck.getExitCode() != null && existenceCheck.getExitCode() == 0) {
            return;
        }
        Path parent = target.getParent();
        if (parent != null) {
            sandbox.files().createDirectories(List.of(WriteEntry.builder()
                    .path(parent.toString())
                    .mode(755)
                    .build()));
        }
        sandbox.files().writeFile(target.toString(), content);
    }

    private Sandbox renewIfNeeded(String userId, SandboxEntry entry) {
        Duration remaining = Duration.between(OffsetDateTime.now(ZoneOffset.UTC), entry.expiresAt());
        Duration threshold = Duration.ofMinutes(Math.max(0, properties.getRenewThresholdMinutes()));
        if (remaining.compareTo(threshold) > 0) {
            return entry.sandbox();
        }

        Duration extension = Duration.ofMinutes(positive(properties.getTimeoutMinutes(), 60));
        SandboxRenewResponse response = entry.sandbox().renew(extension);
        OffsetDateTime expiresAt = response.getExpiresAt();
        if (expiresAt == null) {
            expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plus(extension);
        }
        SandboxEntry renewed = new SandboxEntry(entry.sandbox(), expiresAt);
        localSandboxes.put(userId, renewed);
        persistMapping(userId, entry.sandbox().getId(), expiresAt);
        log.info("已续期用户 {} 的 Aone 沙箱 {} 至 {}", userId, entry.sandbox().getId(), expiresAt);
        return entry.sandbox();
    }

    private void persistMapping(String userId, String sandboxId, OffsetDateTime expiresAt) {
        Duration ttl = Duration.between(OffsetDateTime.now(ZoneOffset.UTC), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            ttl = Duration.ofSeconds(1);
        }
        redisTemplate.opsForValue().set(userMappingKey(userId), sandboxId, ttl);
        redisTemplate.opsForZSet().add(activeSandboxesKey(), sandboxId, expiresAt.toInstant().toEpochMilli());
    }

    private void acquireCreateLock(String ownerToken) {
        int lockSeconds = Math.max(
                positive(properties.getCreateLockSeconds(), 30),
                positive(properties.getReadyTimeoutSeconds(), 120) + 30);
        long deadline = System.nanoTime() + Duration.ofSeconds(lockSeconds).toNanos();
        do {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(createLockKey(), ownerToken, Duration.ofSeconds(lockSeconds));
            if (Boolean.TRUE.equals(acquired)) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待 Aone 沙箱创建锁时被中断", ex);
            }
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("等待 Aone 沙箱创建锁超时，请稍后重试");
    }

    private ConnectionConfig connectionConfig() {
        int requestTimeoutSeconds = Math.max(
                30,
                Math.max(
                        positive(properties.getReadyTimeoutSeconds(), 120),
                        positive(properties.getExecTimeoutSeconds(), 120)) + 30);
        return ConnectionConfig.builder()
                .apiKey(properties.getApiKey().trim())
                .requestTimeout(Duration.ofSeconds(requestTimeoutSeconds))
                .build();
    }

    private String userMappingKey(String userId) {
        String encodedUserId = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(userId.getBytes(StandardCharsets.UTF_8));
        return keyPrefix() + "user:" + encodedUserId;
    }

    private String activeSandboxesKey() {
        return keyPrefix() + "active";
    }

    private String createLockKey() {
        return keyPrefix() + "create-lock";
    }

    private String keyPrefix() {
        return nonBlank(properties.getKeyPrefix(), "sre:sandbox:");
    }

    private boolean isExpired(OffsetDateTime expiresAt) {
        return expiresAt == null || !expiresAt.isAfter(OffsetDateTime.now(ZoneOffset.UTC));
    }

    private void validateConfiguration() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Aone 沙箱功能未启用");
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new IllegalStateException("Aone 沙箱 API Key 未配置");
        }
    }

    private void validateUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("无法识别当前用户，不能分配沙箱");
        }
    }

    private void closeAndRemoveLocal(String userId, SandboxEntry entry) {
        if (entry != null && localSandboxes.remove(userId, entry)) {
            closeQuietly(entry.sandbox());
        }
    }

    private void closeQuietly(Sandbox sandbox) {
        if (sandbox == null) {
            return;
        }
        try {
            // close 只释放本地 SDK 资源；用户共享的远端沙箱由服务端超时回收。
            sandbox.close();
        } catch (RuntimeException ex) {
            log.debug("释放 Aone 沙箱本地资源失败: {}", ex.getMessage());
        }
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    @PreDestroy
    public void close() {
        List<SandboxEntry> entries = new ArrayList<>(localSandboxes.values());
        localSandboxes.clear();
        entries.forEach(entry -> closeQuietly(entry.sandbox()));
        userLocks.clear();
    }

    private record SandboxEntry(Sandbox sandbox, OffsetDateTime expiresAt) {
    }
}
