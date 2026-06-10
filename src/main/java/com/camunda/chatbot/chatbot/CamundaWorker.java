package com.camunda.chatbot.chatbot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.annotation.JobWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class CamundaWorker {

    private static final Logger LOG = LoggerFactory.getLogger(CamundaWorker.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChatService chatService;

    public CamundaWorker(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * Chat Bot task maps {@code caseValidationAgent.responseText} → {@code responseText} for the job.
     * Broker / connector payloads may expose nested structures as Map, JSON string, or Jackson-convertible types.
     */
    public static String extractAssistantText(Map<String, Object> variables) {
        if (variables == null) {
            return "";
        }
        String direct = nonBlankString(variables.get("responseText"));
        if (!direct.isEmpty()) {
            return direct;
        }
        String fromAgent = extractFromAgentObject(variables.get("caseValidationAgent"));
        if (!fromAgent.isEmpty()) {
            return fromAgent;
        }
        return "";
    }

    private static String nonBlankString(Object o) {
        if (o == null) {
            return "";
        }
        String s = String.valueOf(o).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return "";
        }
        return s;
    }

    private static String extractFromAgentObject(Object agent) {
        if (agent == null) {
            return "";
        }
        Map<String, Object> map = normalizeToMap(agent);
        if (map == null || map.isEmpty()) {
            return "";
        }
        for (String key : List.of("responseText", "response", "assistantMessage", "message", "text")) {
            String s = nonBlankString(map.get(key));
            if (!s.isEmpty()) {
                return s;
            }
        }
        Object inner = map.get("caseValidationAgent");
        if (inner != null && inner != agent) {
            return extractFromAgentObject(inner);
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeToMap(Object agent) {
        if (agent instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        if (agent instanceof String s) {
            String t = s.trim();
            if (t.startsWith("{") || t.startsWith("[")) {
                try {
                    return JSON.readValue(t, new TypeReference<>() {});
                } catch (Exception ignored) {
                    return null;
                }
            }
            return null;
        }
        try {
            return JSON.convertValue(agent, new TypeReference<>() {});
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @JobWorker(type = "User_Feedback", autoComplete = false)
    public void handleUserFeedback(final ActivatedJob job) {
        long processInstanceKey = job.getProcessInstanceKey();
        long jobKey = job.getKey();

        Map<String, Object> variables = job.getVariablesAsMap();
        String responseText = extractAssistantText(variables);

        boolean accepted = chatService.addPendingJob(processInstanceKey, jobKey, responseText);
        if (accepted) {
            LOG.info("User_Feedback for process {} job {} (text length {})", processInstanceKey, jobKey, responseText.length());
        } else {
            LOG.info("Ignored stale User_Feedback job {} for process {}", jobKey, processInstanceKey);
        }
    }
}
