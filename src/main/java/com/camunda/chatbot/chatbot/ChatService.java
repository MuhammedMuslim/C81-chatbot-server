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

    public void addPendingJob(Long conversationId, Long jobKey, String responseText) {
        pendingJobs.put(conversationId, jobKey);
        latestResponses.put(conversationId, responseText);
    }

    public Long getPendingJobKey(Long conversationId) {
        return pendingJobs.get(conversationId);
    }

    public void clearPendingJob(Long conversationId) {
        pendingJobs.remove(conversationId);
    }

    public String getLatestResponse(Long conversationId) {
        return latestResponses.get(conversationId);
    }
}
