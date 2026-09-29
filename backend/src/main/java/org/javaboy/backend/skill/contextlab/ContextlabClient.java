package org.javaboy.backend.skill.contextlab;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaboy.backend.config.SreAgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Contextlab（Aone Skills）低层 HTTP 客户端。
 *
 * <p>仅封装两类调用：
 * <ul>
 *   <li>查询 skill 列表：{@code GET /api/resources?resourceType=skill}</li>
 *   <li>下载 skill 压缩包：{@code GET /skill/{fullname}/-/{name}-{version}.zip}</li>
 * </ul>
 */
@Component
public class ContextlabClient {

    private static final Logger log = LoggerFactory.getLogger(ContextlabClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String endpoint;
    private final String token;

    public ContextlabClient(SreAgentProperties properties) {
        SreAgentProperties.ContextlabSkill cfg = properties.getContextlab();
        this.endpoint = cfg.getEndpoint().replaceAll("/$", "");
        this.token = cfg.getToken();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 查询 Contextlab 上可见的 skill 列表（分页）。
     *
     * <p>与 PaasManager 保持一致，支持按 empId 可见、按 ownerEmpId 拥有、按 resourceId 内置平台三种维度。
     */
    public List<ContextlabResourceItem> querySkills(
            String empId,
            String ownerEmpId,
            List<String> scopes,
            List<String> resourceIds,
            boolean includePublic,
            int offset,
            int limit) {

        StringBuilder uri = new StringBuilder(endpoint)
                .append("/api/resources?resourceType=skill")
                .append("&includePublic=").append(includePublic)
                .append("&offset=").append(offset)
                .append("&limit=").append(limit);

        if (StringUtils.hasText(ownerEmpId)) {
            uri.append("&ownerEmpId=").append(urlEncode(ownerEmpId));
        }
        if (StringUtils.hasText(empId)) {
            uri.append("&empId=").append(urlEncode(empId));
        }
        if (!CollectionUtils.isEmpty(resourceIds)) {
            uri.append("&resourceId=").append(urlEncode(String.join(",", resourceIds)));
        }
        if (!CollectionUtils.isEmpty(scopes)) {
            uri.append("&scope=").append(urlEncode(String.join(",", scopes)));
        }

        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(uri.toString()))
                .GET()
                .timeout(Duration.ofSeconds(15));
        addAuthHeader(rb);

        try {
            HttpResponse<String> resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 429) {
                throw new RuntimeException("Contextlab rate limited (429)");
            }
            if (resp.statusCode() != 200) {
                throw new RuntimeException("Contextlab query failed: HTTP " + resp.statusCode());
            }
            ContextlabResourceListResponse body = mapper.readValue(resp.body(), ContextlabResourceListResponse.class);
            return body.data() != null ? body.data() : List.of();
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to query Contextlab skills", e);
        }
    }

    /**
     * 下载指定 skill 指定版本的 zip 包。
     *
     * @param fullName skill 全名，如 {@code my-skill} 或 {@code @scope/my-skill}
     * @param version  版本号，如 {@code 0.1.0}
     */
    public byte[] downloadZip(String fullName, String version) {
        String name = fullName.contains("/") ? fullName.substring(fullName.lastIndexOf('/') + 1) : fullName;
        String path = String.format("/skill/%s/-/%s-%s.zip", fullName, name, version);

        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(endpoint + path))
                .GET()
                .timeout(Duration.ofSeconds(30));
        addAuthHeader(rb);

        try {
            HttpResponse<byte[]> resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                throw new RuntimeException(
                        "Failed to download skill " + fullName + "@" + version + ": HTTP " + resp.statusCode());
            }
            return resp.body();
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to download skill package", e);
        }
    }

    private void addAuthHeader(HttpRequest.Builder rb) {
        if (StringUtils.hasText(token)) {
            rb.header("Authorization", "Bearer " + token);
        }
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
