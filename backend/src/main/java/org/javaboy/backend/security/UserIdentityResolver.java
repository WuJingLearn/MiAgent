package org.javaboy.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.regex.Pattern;

@Component
public class UserIdentityResolver {

    public static final String USER_HEADER = "X-User-Id";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:@-]{1,128}");
    private static final Pattern SAFE_TENANT_ID = Pattern.compile("[A-Za-z0-9._:@-]{1,120}");

    public static final String DEFAULT_USER_ID = "370734";

    public String resolve(HttpServletRequest request) {
        String userId = request.getHeader(USER_HEADER);
        if (userId == null || userId.isBlank()) {
            return DEFAULT_USER_ID;
        }
        if (!SAFE_ID.matcher(userId).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "缺少合法的 " + USER_HEADER + "；生产环境应由认证网关注入");
        }
        return userId;
    }

    public String serviceUser(String tenantId) {
        String normalizedTenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId.trim();
        if (!SAFE_TENANT_ID.matcher(normalizedTenant).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenantId 格式不合法");
        }
        return normalizedTenant + ":sunfire";
    }
}
