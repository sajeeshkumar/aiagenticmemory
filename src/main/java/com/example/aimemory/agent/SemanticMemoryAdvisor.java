package com.example.aimemory.agent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Stage 4 semantic memory: durable, curated <i>facts</i>, as opposed to Stage 3's automatically
 * recorded <i>events</i>.
 *
 * <p>The distinction that matters, made concrete rather than just asserted:
 * <ul>
 *   <li><b>Episodic</b> ({@link EpisodicMemoryAdvisor}) writes something after <i>every</i> turn,
 *       unconditionally, shaped like a transcript ("customer asked X, agent answered Y"), scoped
 *       to one customer.</li>
 *   <li><b>Semantic</b> (here) writes two very different kinds of things, both into
 *       {@code semantic_memory} rather than {@code vector_store}: store policies - seeded once by
 *       {@link SemanticKnowledgeSeeder}, shared by every customer, never tied to a conversation
 *       at all - and customer facts, captured <i>only</i> when the customer says something that
 *       sounds like it's meant to be remembered going forward, stored as a short canonical
 *       statement rather than a Q&amp;A transcript.</li>
 * </ul>
 *
 * <p>A production system would extract customer facts with an LLM call or a tool (that's Stage
 * 5 territory); the phrase-matching heuristic here keeps this stage scoped to retrieval.
 *
 * <p>Run order (500 &lt; 600 &lt; 1000): after {@link EpisodicMemoryAdvisor} so both context
 * blocks land in the prompt before {@link WireTapAdvisor} prints it.
 */
@Component
public class SemanticMemoryAdvisor implements CallAdvisor {

    private static final int ORDER = 600;
    private static final int TOP_K = 4;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    /** Crude stand-in for an LLM-driven fact extractor - good enough to decide "is this durable?". */
    private static final List<String> REMEMBER_TRIGGERS = List.of(
            "remember", "note for my file", "note on my file", "keep on file", "for the record",
            "make a note", "going forward", "please flag");

    private final VectorStore semanticStore;

    public SemanticMemoryAdvisor(@Qualifier(VectorStoreConfig.SEMANTIC_STORE) VectorStore semanticStore) {
        this.semanticStore = semanticStore;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String customerId = (String) request.context().get(EpisodicMemoryAdvisor.CUSTOMER_ID);
        String question = lastUserText(request);

        List<Document> relevant = (customerId == null) ? List.of() : recall(customerId, question);
        ChatClientRequest enriched = relevant.isEmpty() ? request : withSemanticContext(request, relevant);
        ChatClientResponse response = chain.nextCall(enriched);

        if (customerId != null && looksLikeADurableFact(question)) {
            captureCustomerFact(customerId, question);
        }
        return response;
    }

    /** Policies are everyone's; customer facts are scoped - one query covers both. */
    private List<Document> recall(String customerId, String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        FilterExpressionBuilder f = new FilterExpressionBuilder();
        Filter.Expression scopeFilter = f.or(
                f.eq("scope", "global"),
                f.eq("customer_id", customerId)).build();

        SearchRequest search = SearchRequest.builder()
                .query(question)
                .topK(TOP_K)
                .filterExpression(scopeFilter)
                .build();
        return semanticStore.similaritySearch(search);
    }

    private boolean looksLikeADurableFact(String question) {
        if (question == null) {
            return false;
        }
        String lower = question.toLowerCase(Locale.ROOT);
        return REMEMBER_TRIGGERS.stream().anyMatch(lower::contains);
    }

    private void captureCustomerFact(String customerId, String statement) {
        Document fact = Document.builder()
                .text(statement)
                .metadata(Map.of(
                        "scope", "customer",
                        "customer_id", customerId,
                        "captured_at", TIMESTAMP.format(Instant.now())))
                .build();
        semanticStore.add(List.of(fact));
    }

    private ChatClientRequest withSemanticContext(ChatClientRequest request, List<Document> relevant) {
        StringBuilder context = new StringBuilder(
                "Store policies and durable facts on file that may be relevant (use only what's "
                        + "actually relevant to the current question):\n");
        for (Document doc : relevant) {
            String scope = String.valueOf(doc.getMetadata().getOrDefault("scope", "unknown"));
            String label = "global".equals(scope) ? "policy" : "customer file";
            context.append("- [").append(label).append("] ").append(doc.getText().replace("\n", " ")).append('\n');
        }

        List<Message> original = request.prompt().getInstructions();
        List<Message> withContext = new ArrayList<>(original.size() + 1);
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

    @Override
    public String getName() {
        return "semantic-memory";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
