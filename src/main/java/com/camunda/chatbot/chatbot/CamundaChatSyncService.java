package com.camunda.chatbot.chatbot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.enums.JobState;
import io.camunda.client.api.search.enums.ProcessInstanceState;
import io.camunda.client.api.search.response.Job;
import io.camunda.client.api.search.response.ProcessInstance;
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
 *
 * <p>IMPORTANT: search APIs are eventually consistent. Right after a job is completed they can
 * still report it as active together with the previous turn's variables. Every job recovered here
 * is therefore checked against {@link ChatService#getLastCompletedJobKey(Long)} so a stale engine
 * view can never re-introduce an already-answered message (which made replies repeat or vanish).
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
        Long cachedJobKey = chatService.getPendingJobKey(processInstanceKey);
        String cachedText = chatService.getLatestResponse(processInstanceKey);
        if (cachedJobKey != null && cachedText != null && !cachedText.isBlank()) {
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
            Long lastCompletedJobKey = chatService.getLastCompletedJobKey(processInstanceKey);
            Job active = null;
            for (Job j : jobs) {
                if (j == null || Boolean.TRUE.equals(j.isDenied())) {
                    continue;
                }
                Long jobKey = j.getJobKey();
                if (jobKey == null) {
                    continue;
                }
                // Stale index data: we already completed this job, the engine just hasn't exported it yet.
                if (lastCompletedJobKey != null && jobKey <= lastCompletedJobKey) {
                    continue;
                }
                if (isNonTerminalJobState(j.getState())) {
                    if (active == null || jobKey > active.getJobKey()) {
                        active = j;
                    }
                }
            }
            if (active == null) {
                return;
            }
            Long jobKey = active.getJobKey();
            String assistantText = fetchAssistantTextFromVariables(processInstanceKey, active.getElementInstanceKey());
            if (assistantText.isBlank()) {
                LOG.debug("User_Feedback job {} on PI {} has no assistant text yet", jobKey, processInstanceKey);
                return;
            }
            boolean accepted = chatService.addPendingJob(processInstanceKey, jobKey, assistantText);
            if (accepted) {
                LOG.info(
                        "Synced User_Feedback from engine for PI {} job {} (message length {})",
                        processInstanceKey,
                        jobKey,
                        assistantText.length());
            }
        } catch (Exception e) {
            LOG.warn("Could not sync chat state from Camunda for PI {}: {}", processInstanceKey, e.getMessage());
        }
    }

    /** True when the process instance is no longer running (e.g. the 1-hour chat timer fired). */
    public boolean isProcessEnded(long processInstanceKey) {
        try {
            ProcessInstance pi = camundaClient
                    .newProcessInstanceGetRequest(processInstanceKey)
                    .send()
                    .join();
            return pi != null && pi.getState() != null && pi.getState() != ProcessInstanceState.ACTIVE;
        } catch (Exception e) {
            LOG.debug("Could not fetch process instance {}: {}", processInstanceKey, e.getMessage());
            return false;
        }
    }

    private String fetchAssistantTextFromVariables(long processInstanceKey, Long elementInstanceKey) {
        try {
            // The Chat Bot task's input mapping creates responseText as a LOCAL variable in the
            // task's scope. Reading it scoped to this job's element instance guarantees we get the
            // text belonging to THIS turn and never a previous turn's leftover value.
            if (elementInstanceKey != null) {
                String scoped = fetchVariableString(f -> f.scopeKey(elementInstanceKey).name("responseText"));
                if (scoped != null && !scoped.isBlank()) {
                    return scoped.trim();
                }
            }

            String direct = fetchVariableString(f -> f.processInstanceKey(processInstanceKey).name("responseText"));
            if (direct != null && !direct.isBlank()) {
                return direct.trim();
            }

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

    private String fetchVariableString(
            java.util.function.Consumer<io.camunda.client.api.search.filter.VariableFilter> filter) {
        try {
            List<Variable> list = camundaClient
                    .newVariableSearchRequest()
                    .filter(filter)
                    .withFullValues()
                    .page(p -> p.limit(4))
                    .send()
                    .join()
                    .items();
            if (list == null || list.isEmpty()) {
                return null;
            }
            String raw = list.get(0).getValue();
            if (raw == null || raw.isBlank()) {
                return null;
            }
            raw = raw.trim();
            if (raw.length() >= 2 && raw.charAt(0) == '"') {
                try {
                    return JSON.readValue(raw, String.class);
                } catch (Exception e) {
                    return raw;
                }
            }
            if ("null".equalsIgnoreCase(raw)) {
                return null;
            }
            return raw;
        } catch (Exception e) {
            LOG.debug("Could not read variable: {}", e.getMessage());
            return null;
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
