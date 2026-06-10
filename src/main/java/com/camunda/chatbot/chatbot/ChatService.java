package com.camunda.chatbot.chatbot;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChatService {

    // Maps conversationId (processInstanceKey) to the pending jobKey
    private final Map<Long, Long> pendingJobs = new ConcurrentHashMap<>();

    // Maps conversationId to the latest response text from the AI
    private final Map<Long, String> latestResponses = new ConcurrentHashMap<>();

    /**
     * Highest User_Feedback jobKey we have already completed per conversation.
     * Camunda search APIs are eventually consistent: right after completing a job they may still
     * report it as active together with the previous responseText. Job keys of one process instance
     * are generated on the same partition and are strictly increasing, so anything at or below this
     * key is a stale view and must never be re-cached (it caused repeated / missing chat replies).
     */
    private final Map<Long, Long> lastCompletedJobKeys = new ConcurrentHashMap<>();

    /**
     * @return false when the job is stale (already completed) and was ignored.
     */
    public synchronized boolean addPendingJob(Long conversationId, Long jobKey, String responseText) {
        Long lastCompleted = lastCompletedJobKeys.get(conversationId);
        if (lastCompleted != null && jobKey != null && jobKey <= lastCompleted) {
            return false;
        }
        pendingJobs.put(conversationId, jobKey);
        latestResponses.put(conversationId, responseText);
        return true;
    }

    public Long getPendingJobKey(Long conversationId) {
        return pendingJobs.get(conversationId);
    }

    /**
     * Call after completing (or discarding) a job so stale engine views cannot resurrect it.
     * Next assistant turn must not reuse the previous message while the agent runs again.
     */
    public synchronized void markJobCompleted(Long conversationId, Long jobKey) {
        if (jobKey != null) {
            lastCompletedJobKeys.merge(conversationId, jobKey, Math::max);
        }
        pendingJobs.remove(conversationId);
        latestResponses.remove(conversationId);
    }

    public Long getLastCompletedJobKey(Long conversationId) {
        return lastCompletedJobKeys.get(conversationId);
    }

    public String getLatestResponse(Long conversationId) {
        return latestResponses.get(conversationId);
    }
}
