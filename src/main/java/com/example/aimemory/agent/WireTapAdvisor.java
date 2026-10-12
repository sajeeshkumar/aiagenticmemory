package com.example.aimemory.agent;

import java.util.List;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

/**
 * Prints the exact message list handed to the model on every call.
 *
 * <p>This is the whole point of the PoC: memory is not magic, it is what ends up in this list. In
 * Stage 1 it is always "1 system + 1 user" no matter how long the conversation has been running,
 * which is why the agent forgets the order number.
 */
@Component
public class WireTapAdvisor implements CallAdvisor {

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        List<Message> sent = request.prompt().getInstructions();
        System.out.printf("%n  ┌─ what the model actually receives (%d message(s)) ─%n", sent.size());
        for (Message message : sent) {
            System.out.printf("  │ %-9s %s%n", message.getMessageType(), oneLine(message.getText()));
        }
        System.out.println("  └────────────────────────────────────────────────────");
        return chain.nextCall(request);
    }

    private String oneLine(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 160 ? flat : flat.substring(0, 157) + "...";
    }

    @Override
    public String getName() {
        return "wire-tap";
    }

    @Override
    public int getOrder() {
        return 1000; // last in, so it sees whatever earlier advisors added
    }
}
