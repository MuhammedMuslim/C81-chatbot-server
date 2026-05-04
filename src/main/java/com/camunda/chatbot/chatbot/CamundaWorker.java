package com.camunda.chatbot.chatbot;

import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class CamundaWorker {

    private static final Logger LOG = LoggerFactory.getLogger(CamundaWorker.class);
    private final ChatService chatService;

    public CamundaWorker(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * Chat Bot task maps {@code caseValidationAgent.responseText} → {@code responseText} for the job.
     * Read both flat and nested shapes depending on broker serialization.
     */
    static String extractAssistantText(Map<String, Object> variables) {
        if (variables == null) {
            return "";
        }
        Object direct = variables.get("responseText");
        if (direct != null) {
            return String.valueOf(direct);
        }
        Object agent = variables.get("caseValidationAgent");
        if (agent instanceof Map<?, ?> map) {
            Object nested = map.get("responseText");
            if (nested != null) {
                return String.valueOf(nested);
            }
            nested = map.get("response");
            if (nested != null) {
                return String.valueOf(nested);
            }
        }
        return "";
    }

    @JobWorker(type = "User_Feedback", autoComplete = false)
    public void handleUserFeedback(final ActivatedJob job) {
        long processInstanceKey = job.getProcessInstanceKey();
        long jobKey = job.getKey();

        Map<String, Object> variables = job.getVariablesAsMap();
        String responseText = extractAssistantText(variables);

        LOG.info("User_Feedback for process {} job {} (text length {})", processInstanceKey, jobKey, responseText.length());

        chatService.addPendingJob(processInstanceKey, jobKey, responseText);
    }
}
