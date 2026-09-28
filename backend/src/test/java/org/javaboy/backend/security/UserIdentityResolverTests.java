package org.javaboy.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserIdentityResolverTests {

    private final UserIdentityResolver resolver = new UserIdentityResolver();

    @Test
    void resolvesTrustedHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(UserIdentityResolver.USER_HEADER, "tenant:user-1");

        assertEquals("tenant:user-1", resolver.resolve(request));
    }

    @Test
    void rejectsMissingHeader() {
        assertThrows(ResponseStatusException.class,
                () -> resolver.resolve(new MockHttpServletRequest()));
    }
}
