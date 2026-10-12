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
 * Seeds the store's playbook of known procedures, the same idempotent-upsert pattern as
 * {@link SemanticKnowledgeSeeder}. Runs before {@code SupportConsole}.
 *
 * <p>In a real system this library would grow: a human agent's novel resolution to a new kind
 * of problem gets written back here so the same situation is handled consistently next time.
 * That write path is deliberately out of scope for this PoC - it's a fixed, curated set, same as
 * {@link SemanticKnowledgeSeeder}'s policies - but the shape of how it would plug in is the same
 * {@code procedureStore.add(List.of(newProcedure))} call used here.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProcedureSeeder implements ApplicationRunner {

    private final VectorStore procedureStore;

    public ProcedureSeeder(@Qualifier(VectorStoreConfig.PROCEDURE_STORE) VectorStore procedureStore) {
        this.procedureStore = procedureStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        procedureStore.add(List.of(
                procedure("proc-return-unopened", "return-unopened-electronics", """
                        1. Confirm the item is unused and still within the 30-day return window \
                        (check policy-returns and the order's delivery date).
                        2. If eligible, call processRefund with the order id - this marks the \
                        order refunded immediately.
                        3. Confirm the refund amount and completion to the customer; mention the \
                        original payment method will be credited."""),
                procedure("proc-failed-delivery", "failed-delivery-redelivery", """
                        1. Check the order's current carrier status and whether a delivery \
                        attempt already failed.
                        2. If within the carrier's 5-business-day hold window (policy-shipping-\
                        delay), offer redelivery or pickup and call rescheduleDelivery with a \
                        new date the customer agrees to.
                        3. If the hold window has passed, call escalateToHuman instead - this \
                        procedure does not cover a returned-to-sender package."""),
                procedure("proc-prolonged-backorder", "prolonged-backorder", """
                        1. Check how long the item has been backordered.
                        2. If the wait exceeds 2 weeks, call applyBackorderStoreCredit with a \
                        10 percent goodwill credit (policy-backorder).
                        3. Offer the customer the choice of continuing to wait for restock or \
                        accepting an already-offered substitute size/color at no extra cost.""")));
    }

    private Document procedure(String id, String name, String steps) {
        return Document.builder()
                .id(id)
                .text(steps)
                .metadata(Map.of("procedure_name", name))
                .build();
    }
}
