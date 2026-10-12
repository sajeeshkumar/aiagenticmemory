package com.example.aimemory.agent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Stage 3 episodic memory, as an advisor - the same "load before, save after" shape Stage 2's
 * {@code MessageChatMemoryAdvisor} uses for session state, just backed by pgvector and keyed by
 * meaning instead of by conversation id.
 *
 * <p>Session state ({@link RedisChatMemoryRepository}) only ever looks inside the current
 * conversation id; it has nothing to say about a customer who closed the chat last week and
 * opened a brand new one today. This advisor is what lets that happen: every turn is embedded and
 * stored as its own "episode" tagged with a customer id, and every new question first does a
 * similarity search over that customer's past episodes - across every session they've ever had.
 *
 * <p>Run order sits between the two others on purpose: after {@code MessageChatMemoryAdvisor}
 * (order {@code DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER}, very low, so it runs first and the current
 * session's history is already in the prompt) and before {@link WireTapAdvisor} (order 1000, so
 * the printed message list shows the retrieved episodes too).
 */
@Component
public class EpisodicMemoryAdvisor implements CallAdvisor {

    /** Context param: which customer's episodes to recall and record. Required for this advisor to act. */
    public static final String CUSTOMER_ID = "episodic_customer_id";

    /** Context param: a human-readable label for the current session, stored with each episode. */
    public static final String SESSION_LABEL = "episodic_session_label";

    private static final int ORDER = 500;
    private static final int TOP_K = 3;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final VectorStore vectorStore;

    // Stage 4 adds a second VectorStore bean (semantic_memory), so the episodic one
    // (vector_store table, see VectorStoreConfig) now needs to be asked for by name.
    public EpisodicMemoryAdvisor(@Qualifier(VectorStoreConfig.EPISODIC_STORE) VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String customerId = (String) request.context().get(CUSTOMER_ID);
        if (customerId == null || customerId.isBlank()) {
            // No identity to scope episodes to - nothing this advisor can safely recall or record.
            return chain.nextCall(request);
        }

        String question = lastUserText(request);
        List<Document> recalled = recall(customerId, question);

        ChatClientRequest enriched = recalled.isEmpty() ? request : withEpisodicContext(request, recalled);
        ChatClientResponse response = chain.nextCall(enriched);

        String answer = response.chatResponse() == null ? "" : response.chatResponse().getResult().getOutput().getText();
        remember(customerId, (String) request.context().getOrDefault(SESSION_LABEL, "session"), question, answer);

        return response;
    }

    private List<Document> recall(String customerId, String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        SearchRequest search = SearchRequest.builder()
                .query(question)
                .topK(TOP_K)
                .filterExpression("customer_id == '" + sanitize(customerId) + "'")
                .build();
        return vectorStore.similaritySearch(search);
    }

    private void remember(String customerId, String sessionLabel, String question, String answer) {
        if (question == null || question.isBlank() || answer == null || answer.isBlank()) {
            return;
        }
        String episodeText = "Customer asked: " + question + "\nAgent answered: " + answer;
        Document episode = Document.builder()
                .text(episodeText)
                .metadata(Map.of(
                        "customer_id", customerId,
                        "session_label", sessionLabel,
                        "recorded_at", TIMESTAMP.format(Instant.now())))
                .build();
        vectorStore.add(List.of(episode));
    }

    private ChatClientRequest withEpisodicContext(ChatClientRequest request, List<Document> recalled) {
        StringBuilder context = new StringBuilder(
                "Relevant earlier interactions with this same customer (may be from a different session - "
                        + "use them only if they're actually relevant to the current question):\n");
        for (Document doc : recalled) {
            Object recordedAt = doc.getMetadata().get("recorded_at");
            context.append("- [").append(recordedAt).append("] ").append(doc.getText().replace("\n", " ")).append('\n');
        }

        List<Message> original = request.prompt().getInstructions();
        List<Message> withContext = new ArrayList<>(original.size() + 1);
        // Insert right before the final (current) user message, so it reads as fresh context for
        // the question that's about to be asked, not as part of the system preamble.
        withContext.addAll(original.subList(0, original.size() - 1));
        withContext.add(new SystemMessage(context.toString()));
        withContext.add(original.get(original.size() - 1));

        Prompt enrichedPrompt = new Prompt(withContext, request.prompt().getOptions());
        return request.mutate().prompt(enrichedPrompt).build();
    }

    private String lastUserText(ChatClientRequest request) {
        Message last = request.prompt().getLastUserOrToolResponseMessage();
        return last instanceof UserMessage user ? user.getText() : null;
    }

    /** Keeps a stray quote in a user-supplied id from breaking the filter expression syntax. */
    private String sanitize(String value) {
        return value.replace("'", "");
    }

    @Override
    public String getName() {
        return "episodic-memory";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
