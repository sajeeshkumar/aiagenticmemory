package com.example.aimemory.agent;

import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seeds the store-wide policy knowledge half of semantic memory - the half that has nothing to
 * do with any one customer or conversation, which is exactly what sets it apart from
 * {@link EpisodicMemoryAdvisor}'s per-customer episodes.
 *
 * <p>Runs before {@code SupportConsole} ({@code @Order(HIGHEST_PRECEDENCE)}), and is safe to run
 * on every startup: pgvector upserts on id, so re-seeding the same four ids is a no-op once the
 * content stops changing.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SemanticKnowledgeSeeder implements ApplicationRunner {

    private final VectorStore semanticStore;

    public SemanticKnowledgeSeeder(@Qualifier(VectorStoreConfig.SEMANTIC_STORE) VectorStore semanticStore) {
        this.semanticStore = semanticStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        semanticStore.add(List.of(
                policy("policy-returns",
                        "Items may be returned within 30 days of delivery for a full refund, provided "
                                + "they are unused and in original packaging. Worn footwear and opened "
                                + "personal-care electronics (e.g. earbuds) cannot be returned for hygiene "
                                + "reasons, but may be exchanged once for a manufacturing defect."),
                policy("policy-warranty",
                        "All electronics carry a 1-year manufacturer warranty against defects. Accidental "
                                + "damage (drops, water, cracked screens) is not covered by the standard "
                                + "warranty. Customers on the Extended Care Plan get one no-questions-asked "
                                + "accidental-damage replacement per year."),
                policy("policy-shipping-delay",
                        "If a delivery attempt fails, the carrier holds the package at a local facility "
                                + "for 5 business days before returning it to sender. The customer can "
                                + "request redelivery or pickup via the tracking link in that window."),
                policy("policy-backorder",
                        "When a specific size or color is backordered, the customer may either wait for "
                                + "restock - with a 10% store-credit goodwill if the wait exceeds 2 weeks - "
                                + "or accept a substitute size/color at no extra cost.")));
    }

    private Document policy(String id, String text) {
        return Document.builder()
                .id(id)
                .text(text)
                .metadata(Map.of("scope", "global", "type", "policy"))
                .build();
    }
}
