package com.example.aimemory.domain;

/**
 * A single order in the fake back-office system.
 *
 * <p>This is the agent's <em>system of record</em>, not a memory: it is looked up fresh on every
 * request and nothing the conversation does changes it.
 */
public record Order(
        String id,
        String item,
        String status,
        String placedOn,
        String carrier,
        String tracking,
        String eta,
        String notes) {

    public String asLine() {
        return "%s | %s | status=%s | placed=%s | carrier=%s | tracking=%s | eta=%s | %s"
                .formatted(id, item, status, placedOn, carrier, tracking, eta, notes);
    }
}
