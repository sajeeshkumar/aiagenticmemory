package com.example.aimemory.agent;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * The "recall how" half of Stage 5's procedural memory - exposed as a tool the model calls
 * explicitly, not as an advisor that auto-injects context. That's the structural difference from
 * {@link EpisodicMemoryAdvisor} and {@link SemanticMemoryAdvisor}: those two decide for the model
 * what it already knows before it even starts thinking; this one the model has to decide to use,
 * the same way a person consults a runbook instead of having it read aloud whether they need it
 * or not.
 */
@Component
public class ProcedureLibrary {

    private static final int TOP_K = 1;
    private static final double MIN_CONFIDENCE = 0.3;

    private final VectorStore procedureStore;

    public ProcedureLibrary(@Qualifier(VectorStoreConfig.PROCEDURE_STORE) VectorStore procedureStore) {
        this.procedureStore = procedureStore;
    }

    @Tool(name = "findProcedure", description = "Look up the store's known step-by-step procedure "
            + "for handling a customer situation (e.g. returning an unopened item, a prolonged "
            + "backorder, a failed delivery) BEFORE acting on your own. Call this first whenever "
            + "a situation looks like it might have a standard playbook; if it returns no match, "
            + "use your own judgement or escalate.")
    public String findProcedure(
            @ToolParam(description = "a short description of the customer's situation, in your own words")
            String situation) {

        List<Document> matches = procedureStore.similaritySearch(SearchRequest.builder()
                .query(situation)
                .topK(TOP_K)
                .similarityThreshold(MIN_CONFIDENCE)
                .build());

        if (matches.isEmpty()) {
            System.out.printf("  [tool] findProcedure(%s) -> no known procedure%n", situation);
            return "No known procedure matches this situation. Use your own judgement, or call "
                    + "escalateToHuman if it needs a person.";
        }

        Document best = matches.get(0);
        String name = String.valueOf(best.getMetadata().getOrDefault("procedure_name", best.getId()));
        System.out.printf("  [tool] findProcedure(%s) -> matched '%s'%n", situation, name);
        return "Known procedure \"" + name + "\":\n" + best.getText();
    }
}
