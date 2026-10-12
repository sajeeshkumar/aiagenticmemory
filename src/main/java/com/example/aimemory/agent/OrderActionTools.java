package com.example.aimemory.agent;

import java.time.LocalDate;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.example.aimemory.domain.OrderCatalog;

/**
 * Stage 5's "doing" half of procedural memory: real actions the model can execute, not just talk
 * about. Everything before this stage could only read {@link OrderCatalog} and describe it;
 * these tools mutate it, which is what makes the result of following a procedure checkable
 * after the fact instead of only readable in the model's reply.
 *
 * <p>Each method prints a one-line trace when called - a tool-call equivalent of
 * {@link WireTapAdvisor}, since the chat model resolves tool calls internally (Spring AI's
 * default tool-execution loop never surfaces the intermediate tool_call/tool_result messages
 * back up to the advisor chain, so {@code WireTapAdvisor} alone wouldn't show this happening).
 */
@Component
public class OrderActionTools {

    private final OrderCatalog catalog;

    public OrderActionTools(OrderCatalog catalog) {
        this.catalog = catalog;
    }

    @Tool(description = "Process a full refund for an order and mark it refunded. Only call this "
            + "after confirming the order is eligible (unused, within the return window, or "
            + "otherwise per policy) - this immediately changes the order's real status.")
    public String processRefund(@ToolParam(description = "the order id, e.g. ORD-1002") String orderId) {
        var updated = catalog.updateStatus(orderId, "REFUNDED",
                "Refund processed by agent on " + LocalDate.now() + " via procedural tool call.");
        trace("processRefund", orderId, updated.isPresent());
        return updated.map(o -> "Refund processed. " + o.asLine())
                .orElse("No such order: " + orderId);
    }

    @Tool(description = "Reschedule a delivery or redelivery attempt to a new estimated date.")
    public String rescheduleDelivery(
            @ToolParam(description = "the order id, e.g. ORD-1001") String orderId,
            @ToolParam(description = "new estimated delivery date, ISO format yyyy-MM-dd") String newEta) {
        var updated = catalog.updateEta(orderId, newEta,
                "Redelivery rescheduled to " + newEta + " by agent on " + LocalDate.now() + ".");
        trace("rescheduleDelivery", orderId + ", " + newEta, updated.isPresent());
        return updated.map(o -> "Redelivery rescheduled. " + o.asLine())
                .orElse("No such order: " + orderId);
    }

    @Tool(description = "Apply a store-credit goodwill percentage to an order for a prolonged "
            + "backorder delay, per policy. Does not change order status, only adds a note.")
    public String applyBackorderStoreCredit(
            @ToolParam(description = "the order id, e.g. ORD-1003") String orderId,
            @ToolParam(description = "goodwill percentage to credit, e.g. 10") int percent) {
        var updated = catalog.appendNoteOnly(orderId,
                percent + "% store credit issued by agent on " + LocalDate.now() + " for backorder delay.");
        trace("applyBackorderStoreCredit", orderId + ", " + percent + "%", updated.isPresent());
        return updated.map(o -> "Store credit applied. " + o.asLine())
                .orElse("No such order: " + orderId);
    }

    @Tool(description = "Escalate an issue to a human agent when no known procedure covers it, "
            + "or the customer explicitly asks for a person. Does not change any order.")
    public String escalateToHuman(@ToolParam(description = "a short reason for the escalation") String reason) {
        trace("escalateToHuman", reason, true);
        return "Escalated to a human agent (ticket opened). Reason on file: " + reason;
    }

    private void trace(String tool, String args, boolean succeeded) {
        System.out.printf("  [tool] %s(%s) -> %s%n", tool, args, succeeded ? "ok" : "order not found");
    }
}
