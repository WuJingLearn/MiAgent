package org.javaboy.backend.service;

import org.javaboy.backend.repository.SessionLeaseRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@Service
public class SessionLeaseService {

    private final SessionLeaseRepository repository;

    public SessionLeaseService(SessionLeaseRepository repository) {
        this.repository = repository;
    }

    public Mono<Lease> acquire(String userId, String sessionId, int leaseSeconds) {
        String sessionKey = userId + ":" + sessionId;
        String ownerToken = UUID.randomUUID().toString();
        return Mono.fromCallable(() -> {
                    if (!repository.tryAcquire(sessionKey, ownerToken, leaseSeconds)) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "该会话正在处理上一条消息");
                    }
                    return new Lease(sessionKey, ownerToken);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> release(Lease lease) {
        return Mono.fromRunnable(() -> repository.release(lease.sessionKey(), lease.ownerToken()))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    public record Lease(String sessionKey, String ownerToken) {
    }
}
