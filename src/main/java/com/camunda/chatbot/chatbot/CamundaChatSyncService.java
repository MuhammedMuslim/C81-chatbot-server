package com.camunda.chatbot.chatbot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.enums.JobState;
import io.camunda.client.api.search.response.Job;
import io.camunda.client.api.search.response.Variable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * When GET /api/chat/{id} runs, {@link ChatService} is normally filled by {@link CamundaWorker}.
 * If the job worker never claims the SaaS job (or runs elsewhere), the cache stays empty and the UI
 * sees {@code waiting} forever. This service repairs state from Camunda search APIs.
 */
@Service
public class CamundaChatSyncService {

    private static final Logger LOG = LoggerFactory.getLogger(CamundaChatSyncService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final CamundaClient camundaClient;
    private final ChatService chatService;

    @Value("${camunda.chat.user-feedback-job-type:User_Feedback}")
    private String userFeedbackJobType;

    @Value("${camunda.chat.agent-variable-name:caseValidationAgent}")
    private String agentVariableName;

    public CamundaChatSyncService(CamundaClient camundaClient, ChatService chatService) {
        this.camundaClient = camundaClient;
        this.chatService = chatService;
    }

    public void syncPendingUserFeedbackFromEngine(long processInstanceKey) {
        if (chatService.getPendingJobKey(processInstanceKey) != null) {
            return;
        }
        try {
            List<Job> jobs = camundaClient
                    .newJobSearchRequest()
                    .filter(f -> f.processInstanceKey(processInstanceKey).type(userFeedbackJobType))
                    .page(p -> p.limit(32))
                    .send()
                    .join()
                    .items();
            Job active = null;
            for (Job j : jobs) {
                if (j == null || Boolean.TRUE.equals(j.isDenied())) {
                    continue;
                }
                if (isNonTerminalJobState(j.getState())) {
                    active = j;
                    break;
                }
            }
            if (active == null) {
                return;
            }
            Long jobKey = active.getJobKey();
            if (jobKey == null) {
                return;
            }
            String assistantText = fetchAssistantTextFromVariables(processInstanceKey);
            LOG.info(
                    "Synced User_Feedback from engine for PI {} job {} (message length {})",
                    processInstanceKey,
                    jobKey,
                    assistantText.length());
            chatService.addPendingJob(processInstanceKey, jobKey, assistantText);
        } catch (Exception e) {
            LOG.warn("Could not sync chat state from Camunda for PI {}: {}", processInstanceKey, e.getMessage());
        }
    }

    private String fetchAssistantTextFromVariables(long processInstanceKey) {
        try {
            List<Variable> vars = camundaClient
                    .newVariableSearchRequest()
                    .filter(f -> f.processInstanceKey(processInstanceKey).name(agentVariableName))
                    .withFullValues()
                    .page(p -> p.limit(8))
                    .send()
                    .join()
                    .items();
            if (vars == null || vars.isEmpty()) {
                return "";
            }
            String raw = vars.get(0).getValue();
            if (raw == null || raw.isBlank()) {
                return "";
            }
            Map<String, Object> asMap = new HashMap<>();
            try {
                Object parsed = JSON.readValue(raw, new TypeReference<>() {});
                asMap.put("caseValidationAgent", parsed);
            } catch (Exception e) {
                asMap.put("caseValidationAgent", raw);
            }
            return CamundaWorker.extractAssistantText(asMap);
        } catch (Exception e) {
            LOG.warn("Variable search for {} on PI {}: {}", agentVariableName, processInstanceKey, e.getMessage());
            return "";
        }
    }

    private static boolean isNonTerminalJobState(JobState state) {
        if (state == null) {
            return false;
        }
        return state != JobState.COMPLETED
                && state != JobState.CANCELED
                && state != JobState.FAILED
                && state != JobState.ERROR_THROWN
                && state != JobState.TIMED_OUT;
    }
}
