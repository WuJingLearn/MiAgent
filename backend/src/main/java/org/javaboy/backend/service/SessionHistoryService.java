package org.javaboy.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaboy.backend.config.SreAgentProperties;
import org.javaboy.backend.repository.ChatSessionRepository;
import org.javaboy.backend.web.dto.ChatDtos.HistoryMessage;
import org.javaboy.backend.web.dto.ChatDtos.HistoryToolCall;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 AgentScope 的会话状态存储（agentscope_sessions.state_data）中还原一段会话的历史对话，
 * 供前端在切换到历史会话时回显。state_data 是 HarnessAgent 落库的 JSON，其中 context 数组
 * 按时间顺序保存了 USER / ASSISTANT / TOOL 三类消息。
 */
@Service
public class SessionHistoryService {

    private static final String AGENT_STATE_KEY = "agent_state";
    /** Msg 元数据中记录生成原因的 key（见 io.agentscope.core.message.Msg#METADATA_GENERATE_REASON）。 */
    private static final String METADATA_GENERATE_REASON = "agentscope_generate_reason";
    private static final String REASON_INTERRUPTED = "INTERRUPTED";

    private final JdbcTemplate jdbcTemplate;
    private final ChatSessionRepository chatSessionRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String selectStateSql;

    public SessionHistoryService(
            JdbcTemplate jdbcTemplate,
            ChatSessionRepository chatSessionRepository,
            SreAgentProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.chatSessionRepository = chatSessionRepository;
        // 表名来自可信配置，不来自用户输入，直接拼接安全。
        this.selectStateSql = "SELECT state_data FROM " + properties.getAgentStateTable()
                + " WHERE session_id = ? AND state_key = ? ORDER BY item_index LIMIT 1";
    }

    public List<HistoryMessage> loadMessages(String userId, String sessionId) {
        String stateData = findStateData(userId + ":" + sessionId);
        if (stateData == null || stateData.isBlank()) {
            return List.of();
        }
        try {
            List<HistoryMessage> messages = new ArrayList<>();
            boolean trailingInterrupted = parse(stateData, messages);
            if (trailingInterrupted) {
                // 中断轮的半截正文不会落 state_data（框架只追加了被过滤的恢复提示），
                // 用 chat_turn.response_text 兼底补一条 stopped 助手消息，保证刷新后仍能看到已生成内容。
                chatSessionRepository.findLatestTurnResponse(sessionId)
                        .filter(text -> text != null && !text.isBlank())
                        .ifPresent(partial -> messages.add(new HistoryMessage(
                                null, "assistant", partial, "",
                                Instant.now().toString(), "stopped", List.of())));
            }
            return messages;
        } catch (Exception ex) {
            throw new IllegalStateException("解析会话历史失败: " + ex.getMessage(), ex);
        }
    }

    private String findStateData(String stateSessionId) {
        try {
            return jdbcTemplate.queryForObject(selectStateSql, String.class, stateSessionId, AGENT_STATE_KEY);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private boolean parse(String stateData, List<HistoryMessage> messages) throws Exception {
        JsonNode root = objectMapper.readTree(stateData);
        // MysqlAgentStateStore 可能按数组或对象落库，两种都兼容。
        if (root.isArray() && !root.isEmpty()) {
            root = root.get(0);
        }
        JsonNode context = root.path("context");
        if (!context.isArray()) {
            return false;
        }

        // 工具执行结果在独立的 TOOL 轮里，先按 toolCallId 建索引，供助手轮的 tool_use 关联。
        Map<String, JsonNode> toolResults = indexToolResults(context);

        for (JsonNode item : context) {
            String role = item.path("role").asText("");
            if ("USER".equalsIgnoreCase(role)) {
                String text = collectText(item.path("content"));
                messages.add(message(item, "user", text, "", List.of()));
            } else if ("ASSISTANT".equalsIgnoreCase(role)) {
                // 用户主动中断时，框架会追加一条 GenerateReason.INTERRUPTED 的“恢复提示”消息
                // （"I noticed that you have interrupted me..."），它不是真实回复，回显时应过滤掉；
                // 中断前已生成的半截正文/思考/工具调用属于另一条消息（不带 INTERRUPTED），予以保留。
                if (isInterruptedRecovery(item)) {
                    continue;
                }
                JsonNode content = item.path("content");
                String text = collectText(content);
                String reasoning = collectThinking(content);
                List<HistoryToolCall> tools = collectTools(content, toolResults);
                // 纯 tool_use / 纯思考（无正文）的助手轮也保留，以便展示完整过程。
                if (!text.isBlank() || !reasoning.isBlank() || !tools.isEmpty()) {
                    messages.add(message(item, "assistant", text, reasoning, tools));
                }
            }
            // TOOL 角色为工具执行结果，已在助手消息的工具记录中关联展示，无需单独成条。
        }
        // 若 context 末尾是被过滤的中断恢复消息，说明最近一轮是被中断的（半截正文需兼底）。
        int size = context.size();
        if (size > 0) {
            JsonNode last = context.get(size - 1);
            return "ASSISTANT".equalsIgnoreCase(last.path("role").asText("")) && isInterruptedRecovery(last);
        }
        return false;
    }

    /** 判断某条 context 消息是否为中断产生的“恢复提示”消息。 */
    private boolean isInterruptedRecovery(JsonNode item) {
        String reason = item.path("metadata").path(METADATA_GENERATE_REASON).asText("");
        return REASON_INTERRUPTED.equalsIgnoreCase(reason);
    }

    private Map<String, JsonNode> indexToolResults(JsonNode context) {
        Map<String, JsonNode> index = new HashMap<>();
        for (JsonNode item : context) {
            JsonNode content = item.path("content");
            if (!content.isArray()) {
                continue;
            }
            for (JsonNode part : content) {
                if ("tool_result".equals(part.path("type").asText())) {
                    String id = part.path("id").asText(null);
                    if (id != null) {
                        index.put(id, part);
                    }
                }
            }
        }
        return index;
    }

    private HistoryMessage message(JsonNode item, String role, String content, String reasoning, List<HistoryToolCall> tools) {
        return new HistoryMessage(
                item.path("id").asText(null),
                role,
                content,
                reasoning,
                toIso(item.path("timestamp").asText(null)),
                "complete",
                tools);
    }

    private String collectThinking(JsonNode content) {
        if (!content.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : content) {
            if ("thinking".equals(part.path("type").asText())) {
                sb.append(part.path("thinking").asText(""));
            }
        }
        return sb.toString();
    }

    private String collectText(JsonNode content) {
        if (!content.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : content) {
            if ("text".equals(part.path("type").asText())) {
                sb.append(part.path("text").asText(""));
            }
        }
        return sb.toString();
    }

    private List<HistoryToolCall> collectTools(JsonNode content, Map<String, JsonNode> toolResults) {
        if (!content.isArray()) {
            return List.of();
        }
        List<HistoryToolCall> tools = new ArrayList<>();
        for (JsonNode part : content) {
            if ("tool_use".equals(part.path("type").asText())) {
                String id = part.path("id").asText(null);
                JsonNode input = part.path("input");
                JsonNode resultNode = id == null ? null : toolResults.get(id);
                String result = resultNode == null ? "" : collectToolOutput(resultNode.path("output"));
                String resultState = resultNode == null ? null : resultNode.path("state").asText(null);
                tools.add(new HistoryToolCall(
                        id,
                        part.path("name").asText("未命名工具"),
                        "completed",
                        input.isMissingNode() || input.isNull() ? "" : input.toString(),
                        result,
                        resultState));
            }
        }
        return tools;
    }

    /**
     * 工具结果 output 为 [{type:text,text:...}] 结构，拼接其中的文本片段。
     */
    private String collectToolOutput(JsonNode output) {
        if (!output.isArray()) {
            return output.isTextual() ? output.asText("") : "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : output) {
            if ("text".equals(part.path("type").asText())) {
                sb.append(part.path("text").asText(""));
            }
        }
        return sb.toString();
    }

    /**
     * 把 "2026-09-24 20:59:37.860" 归一化为前端 Date 可解析的 ISO 形式；无值时返回 null。
     */
    private String toIso(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        return timestamp.trim().replace(' ', 'T');
    }
}
