package org.javaboy.backend.skill.contextlab;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Contextlab 资源视图接口 /api/resources 返回的分页列表。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContextlabResourceListResponse(
        List<ContextlabResourceItem> data,
        int total,
        int offset,
        int limit
) {
}
