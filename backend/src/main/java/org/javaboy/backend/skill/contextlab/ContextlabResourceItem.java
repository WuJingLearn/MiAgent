package org.javaboy.backend.skill.contextlab;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Contextlab 资源视图接口 /api/resources 返回的单个 skill 条目。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContextlabResourceItem(
        String source,
        String resourceName,
        String description,
        String latestVersion,
        String tarballUrl,
        Map<String, Object> config,
        Map<String, Object> channels,
        Map<String, Object> metadata,
        String lastPublishTime
) {
}
