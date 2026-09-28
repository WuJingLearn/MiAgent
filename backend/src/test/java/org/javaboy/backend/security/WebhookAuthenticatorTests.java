package org.javaboy.backend.security;

import org.javaboy.backend.config.SreAgentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebhookAuthenticatorTests {

    @Test
    void acceptsMatchingTokenAndRejectsOtherTokens() {
        SreAgentProperties properties = new SreAgentProperties();
        properties.setSunfireWebhookToken("secret");
        WebhookAuthenticator authenticator = new WebhookAuthenticator(properties);

        assertDoesNotThrow(() -> authenticator.verify("secret"));
        assertThrows(ResponseStatusException.class, () -> authenticator.verify("wrong"));
    }
}
