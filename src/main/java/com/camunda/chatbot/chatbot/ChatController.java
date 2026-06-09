package com.camunda.chatbot.chatbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.response.Variable;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;



@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
public class ChatController {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(ChatController.class);
    private final CamundaClient camundaClient;
    private final ChatService chatService;
    private final CamundaChatSyncService camundaChatSyncService;

    @Value("${camunda.bpmn.process-id:Process_xadodio_chat}")
    private String bpmnProcessId;

    @Value("${openai.api.key:}")
    private String openaiApiKey;

    @Value("${camunda.chat.max-attachment-count:5}")
    private int maxAttachmentCount;

    @Value("${camunda.chat.max-attachment-bytes-per-file:5242880}")
    private long maxAttachmentBytesPerFile;

    public ChatController(
            CamundaClient camundaClient,
            ChatService chatService,
            CamundaChatSyncService camundaChatSyncService) {
        this.camundaClient = camundaClient;
        this.chatService = chatService;
        this.camundaChatSyncService = camundaChatSyncService;
    }

    /** JSON part for one file (base64 body). */
    public static class AttachmentPart {
        public String fileName;
        public String mimeType;
        public String contentBase64;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true, "bpmnProcessId", bpmnProcessId, "feedbackJobType", "User_Feedback");
    }

    public static class StartFromChatRequest {
        public String message;
        public String studentEmail;
        public String initialMessage;
        public List<AttachmentPart> attachments;
    }

    public static class ReplyRequest {
        public String message;
        public List<AttachmentPart> attachments;
    }

    private static String resolveStartMessage(StartFromChatRequest request) {
        if (request == null) {
            return "";
        }
        if (request.message != null && !request.message.isBlank()) {
            return request.message.trim();
        }
        if (request.initialMessage != null && !request.initialMessage.isBlank()) {
            return request.initialMessage.trim();
        }
        return "";
    }

    private String extractPdfText(byte[] decoded) {
        try (PDDocument document = Loader.loadPDF(decoded)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        } catch (Exception e) {
            LOG.warn("Failed to extract text from PDF: {}", e.getMessage());
            return "[Error extracting text from document]";
        }
    }

    private String appendExtractedText(String text, List<Map<String, Object>> attachments) {
        StringBuilder sb = new StringBuilder(text != null ? text : "");
        for (Map<String, Object> doc : attachments) {
            Object extracted = doc.get("extractedText");
            if (extracted != null && !extracted.toString().isBlank()) {
                sb.append("\n\n[Attached Document '").append(doc.get("filename")).append("']:\n");
                sb.append(extracted.toString().trim());
            }
        }
        return sb.toString();
    }

    private List<Map<String, Object>> buildZeebeAttachments(List<AttachmentPart> parts) {
        if (parts == null || parts.isEmpty()) {
            return List.of();
        }
        if (parts.size() > maxAttachmentCount) {
            throw new IllegalArgumentException("Too many attachments (max " + maxAttachmentCount + ")");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (AttachmentPart p : parts) {
            if (p == null || p.contentBase64 == null || p.contentBase64.isBlank()) {
                continue;
            }
            String name = p.fileName != null && !p.fileName.isBlank() ? p.fileName.trim() : "attachment";
            String mime = p.mimeType != null && !p.mimeType.isBlank() ? p.mimeType.trim() : "application/octet-stream";
            String raw = p.contentBase64.contains(",") ? p.contentBase64.substring(p.contentBase64.indexOf(',') + 1) : p.contentBase64;
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(raw.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid base64 for file: " + name);
            }
            if (decoded.length > maxAttachmentBytesPerFile) {
                throw new IllegalArgumentException("Attachment too large: " + name + " (max " + maxAttachmentBytesPerFile + " bytes)");
            }
            Map<String, Object> doc = new HashMap<>();
            doc.put("filename", name);
            doc.put("mimeType", mime);
            doc.put("contentBase64", raw.trim());
            
            if (mime.toLowerCase().contains("pdf") || name.toLowerCase().endsWith(".pdf")) {
                doc.put("extractedText", extractPdfText(decoded));
            }
            
            out.add(doc);
        }
        return out;
    }

    private static Map<String, Object> currentChatMap(String plainText, List<Map<String, Object>> attachments) {
        Map<String, Object> chat = new HashMap<>();
        chat.put("plainTextBody", plainText);
        chat.put("attachments", attachments);
        return chat;
    }

    private static String absenceSummary(String messageText, List<Map<String, Object>> attachments) {
        if (messageText != null && !messageText.isBlank()) {
            return messageText.trim();
        }
        if (attachments == null || attachments.isEmpty()) {
            return "";
        }
        return "Uploaded " + attachments.size() + " file(s).";
    }

    /** Reads a primitive string variable from the process (used to preserve absenceRequest on attachment-only replies). */
    private String fetchProcessVariableString(long processInstanceKey, String variableName) {
        try {
            List<Variable> list = camundaClient
                    .newVariableSearchRequest()
                    .filter(f -> f.processInstanceKey(processInstanceKey).name(variableName))
                    .withFullValues()
                    .page(p -> p.limit(8))
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
            return raw;
        } catch (Exception e) {
            LOG.warn("Could not read variable {} for PI {}: {}", variableName, processInstanceKey, e.getMessage());
            return null;
        }
    }

    @PostMapping("/start")
    public ResponseEntity<?> startFromChat(@RequestBody StartFromChatRequest request) {
        try {
            String email = request != null && request.studentEmail != null ? request.studentEmail.trim() : "";
            String text = resolveStartMessage(request);
            List<Map<String, Object>> attachmentVars = buildZeebeAttachments(request != null ? request.attachments : null);

            if (text.isEmpty() && attachmentVars.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Send a message and/or at least one attachment to start."));
            }

            String absenceText = absenceSummary(text, attachmentVars);
            String combinedText = appendExtractedText(text.isEmpty() ? absenceText : text, attachmentVars);

            Map<String, Object> variables = new HashMap<>();
            variables.put("absenceRequest", absenceText);
            variables.put("currentChat", currentChatMap(combinedText, attachmentVars));
            variables.put("caseValidationAgent", new HashMap<>());
            variables.put("openAiApiKey", openaiApiKey != null ? openaiApiKey : "");
            
            if (!email.isEmpty()) {
                variables.put("studentEmail", email);
            }

            LOG.info(
                    "Creating process {} from chat (message length {}, attachments {})",
                    bpmnProcessId,
                    text.length(),
                    attachmentVars.size());

            var event = camundaClient
                    .newCreateInstanceCommand()
                    .bpmnProcessId(bpmnProcessId)
                    .latestVersion()
                    .variables(variables)
                    .send()
                    .join();

            long conversationId = event.getProcessInstanceKey();
            // Must be a JSON string: JS cannot safely represent Zeebe keys above 2^53-1 as numbers.
            return ResponseEntity.ok(Map.of("conversationId", String.valueOf(conversationId)));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping("/{conversationId}")
    public ResponseEntity<?> getLatestMessage(@PathVariable("conversationId") String conversationIdRaw) {
        final long conversationId;
        try {
            conversationId = Long.parseLong(conversationIdRaw.trim());
        } catch (NumberFormatException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid conversation id"));
        }
        camundaChatSyncService.syncPendingUserFeedbackFromEngine(conversationId);
        String responseText = chatService.getLatestResponse(conversationId);
        Long pendingJobKey = chatService.getPendingJobKey(conversationId);

        if (pendingJobKey != null) {
            if (responseText == null || responseText.isBlank()) {
                return ResponseEntity.ok(Map.of("status", "waiting"));
            }
            return ResponseEntity.ok(Map.of("status", "pending_user_reply", "message", responseText));
        }

        if (responseText == null || responseText.isBlank()) {
            return ResponseEntity.ok(Map.of("status", "waiting"));
        }

        return ResponseEntity.ok(Map.of("status", "processing", "message", responseText));
    }

    @PostMapping("/{conversationId}")
    public ResponseEntity<?> replyToChat(
            @PathVariable("conversationId") String conversationIdRaw,
            @RequestBody ReplyRequest request) {
        final long conversationId;
        try {
            conversationId = Long.parseLong(conversationIdRaw.trim());
        } catch (NumberFormatException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid conversation id"));
        }
        try {
            String message = request != null && request.message != null ? request.message.trim() : "";
            List<Map<String, Object>> attachmentVars = buildZeebeAttachments(request != null ? request.attachments : null);

            if (message.isEmpty() && attachmentVars.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Send a message and/or at least one attachment."));
            }

            Long jobKey = chatService.getPendingJobKey(conversationId);
            if (jobKey == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "No pending chat request found for this conversation"));
            }

            String absenceText = absenceSummary(message, attachmentVars);
            if (message.isEmpty() && !attachmentVars.isEmpty()) {
                String priorAbsence = fetchProcessVariableString(conversationId, "absenceRequest");
                if (priorAbsence != null && !priorAbsence.isBlank()) {
                    absenceText = priorAbsence;
                }
            }
            String combinedText = appendExtractedText(message.isEmpty() ? absenceText : message, attachmentVars);

            LOG.info(
                    "Completing job {} for process {} (message length {}, attachments {})",
                    jobKey,
                    conversationId,
                    message.length(),
                    attachmentVars.size());

            Map<String, Object> vars = new HashMap<>();
            vars.put("absenceRequest", absenceText);
            vars.put("currentChat", currentChatMap(combinedText, attachmentVars));

            camundaClient.newCompleteCommand(jobKey).variables(vars).send().join();

            chatService.clearPendingJob(conversationId);

            return ResponseEntity.ok(Map.of("status", "sent"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }
}
