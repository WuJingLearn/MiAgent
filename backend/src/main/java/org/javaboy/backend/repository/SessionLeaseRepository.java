package org.javaboy.backend.repository;

import org.javaboy.backend.config.SreAgentProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;

@Repository
public class SessionLeaseRepository {

    /**
     * 释放锁时用 Lua 脚本保证「校验持有者 + 删除」的原子性，避免误删他人续期的锁。
     */
    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('del', KEYS[1]) " +
                    "else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;

    public SessionLeaseRepository(StringRedisTemplate redisTemplate, SreAgentProperties properties) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = properties.getSessionLeaseKeyPrefix();
    }

    /**
     * 通过 Redis 的 {@code SET key value NX PX ttl} 语义原子抢占会话锁。
     *
     * @return true 表示成功持有锁；false 表示锁已被其它请求占用
     */
    public boolean tryAcquire(String sessionKey, String ownerToken, int leaseSeconds) {
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(buildKey(sessionKey), ownerToken, Duration.ofSeconds(leaseSeconds));
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * 仅当锁的持有者令牌与 {@code ownerToken} 一致时才删除，防止误删别人的锁。
     */
    public void release(String sessionKey, String ownerToken) {
        redisTemplate.execute(RELEASE_SCRIPT, List.of(buildKey(sessionKey)), ownerToken);
    }

    private String buildKey(String sessionKey) {
        return keyPrefix + sessionKey;
    }
}
