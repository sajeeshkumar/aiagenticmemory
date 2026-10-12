package com.example.aimemory.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/** In-memory stand-in for the order-management service behind the support desk. */
@Component
public class OrderCatalog {

    private final Map<String, Order> orders = new LinkedHashMap<>();

    public OrderCatalog() {
        add(new Order("ORD-1001", "Sony WH-1000XM5 headphones", "SHIPPED", "2026-10-04",
                "BlueDart", "BD4471920384", "2026-10-13",
                "Left the Pune hub; one delivery attempt already failed (nobody home)."));
        add(new Order("ORD-1002", "Breville Bambino espresso machine", "DELIVERED", "2026-09-26",
                "Delhivery", "DL8891002233", "2026-10-02",
                "Delivered 2026-10-02. Return window closes 2026-11-01."));
        add(new Order("ORD-1003", "Asics Novablast 5, size 9", "PROCESSING", "2026-10-09",
                "-", "-", "2026-10-18",
                "Warehouse backorder on size 9; customer was offered size 9.5 instead."));
        add(new Order("ORD-1004", "Garmin Forerunner 265", "CANCELLED", "2026-09-20",
                "-", "-", "-",
                "Cancelled by customer 2026-09-22. Refund of $449 issued to card on 2026-09-28."));
    }

    private void add(Order order) {
        orders.put(order.id(), order);
    }

    public Optional<Order> find(String orderId) {
        return Optional.ofNullable(orders.get(key(orderId)));
    }

    /**
     * Stage 5's action tools call these - the first thing in this PoC that actually changes
     * the world instead of just answering questions about it. {@link Order} is an immutable
     * record, so "updating" means replacing the map entry; the note is appended, not replaced,
     * so the history of what happened to an order stays readable.
     */
    public Optional<Order> updateStatus(String orderId, String newStatus, String noteAddition) {
        return mutate(orderId, existing -> new Order(existing.id(), existing.item(), newStatus,
                existing.placedOn(), existing.carrier(), existing.tracking(), existing.eta(),
                appendNote(existing.notes(), noteAddition)));
    }

    public Optional<Order> updateEta(String orderId, String newEta, String noteAddition) {
        return mutate(orderId, existing -> new Order(existing.id(), existing.item(), existing.status(),
                existing.placedOn(), existing.carrier(), existing.tracking(), newEta,
                appendNote(existing.notes(), noteAddition)));
    }

    public Optional<Order> appendNoteOnly(String orderId, String noteAddition) {
        return mutate(orderId, existing -> new Order(existing.id(), existing.item(), existing.status(),
                existing.placedOn(), existing.carrier(), existing.tracking(), existing.eta(),
                appendNote(existing.notes(), noteAddition)));
    }

    private Optional<Order> mutate(String orderId, java.util.function.UnaryOperator<Order> mutator) {
        String key = key(orderId);
        Order existing = orders.get(key);
        if (existing == null) {
            return Optional.empty();
        }
        Order updated = mutator.apply(existing);
        orders.put(key, updated);
        return Optional.of(updated);
    }

    private String appendNote(String existingNote, String addition) {
        return (existingNote == null || existingNote.isBlank()) ? addition : existingNote + " | " + addition;
    }

    private String key(String orderId) {
        return orderId == null ? "" : orderId.trim().toUpperCase();
    }

    /** The whole catalog, rendered for a prompt (or for proving a tool call actually changed it). */
    public String asPromptTable() {
        return orders.values().stream().map(Order::asLine).collect(Collectors.joining("\n"));
    }
}
