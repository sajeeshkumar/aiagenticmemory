package com.example.aimemory.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

import com.example.aimemory.domain.OrderCatalog;

/**
 * Stage 1 agent: <b>no memory of any kind.</b>
 *
 * <p>Every call builds a fresh prompt of exactly one system message plus the current user message.
 * The agent therefore has no session state: the order number the customer gave a moment ago is
 * gone, and the next question starts from zero.
 */
@Component("stage1")
public class StatelessSupportAgent implements SupportAgent {

    private final ChatClient chatClient;

    public StatelessSupportAgent(ChatClient.Builder builder,
                                 WireTapAdvisor wireTap,
                                 OrderCatalog catalog,
                                 @Value("classpath:prompts/support-system.st") Resource systemPrompt) throws IOException {

        String system = asString(systemPrompt).replace("{orders}", catalog.asPromptTable());

        this.chatClient = builder
                .defaultSystem(system)
                .defaultAdvisors(wireTap)
                .build();
    }

    /**
     * Answers one question. {@code customerId} and {@code conversationId} are accepted only to
     * satisfy {@link SupportAgent} and are never looked at - there is no history lookup, nothing
     * written back. That's the point: this stage cannot remember even when it's handed an
     * identity and a session to remember by.
     */
    @Override
    public String ask(String customerId, String conversationId, String question) {
        return chatClient.prompt()
                .user(question)
                .call()
                .content();
    }

    @Override
    public String stageLabel() {
        return "Stage 1: no memory";
    }

    private static String asString(Resource resource) throws IOException {
        return FileCopyUtils.copyToString(
                new java.io.InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8));
    }
}
