package org.javaboy.backend.security;

import org.javaboy.backend.config.SreAgentProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class WebhookAuthenticator {

    public static final String TOKEN_HEADER = "X-Sunfire-Token";

    private final SreAgentProperties properties;

    public WebhookAuthenticator(SreAgentProperties properties) {
        this.properties = properties;
    }

    public void verify(String suppliedToken) {
        String expectedToken = properties.getSunfireWebhookToken();
        if (expectedToken == null || expectedToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Sunfire Webhook 鉴权未配置");
        }
        byte[] expected = expectedToken.getBytes(StandardCharsets.UTF_8);
        byte[] actual = suppliedToken == null
                ? new byte[0]
                : suppliedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sunfire Webhook 鉴权失败");
        }
    }
}
