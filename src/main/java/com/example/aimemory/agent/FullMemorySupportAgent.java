package com.example.aimemory.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

import com.example.aimemory.domain.OrderCatalog;

/**
 * Stage 4 agent: Stage 3's full stack (session state + episodic memory) plus
 * {@link SemanticMemoryAdvisor} - durable, curated facts, as opposed to episodic's automatic
 * log of events.
 *
 * <p>Three advisors now sit between the question and the model, each answering a different
 * question about what the agent should already know:
 * <ul>
 *   <li>{@code MessageChatMemoryAdvisor} - "what did we just say, in <i>this</i> conversation?"</li>
 *   <li>{@link EpisodicMemoryAdvisor} - "what has this customer told us before, in <i>any</i>
 *       conversation?"</li>
 *   <li>{@link SemanticMemoryAdvisor} - "what do we <i>know to be true</i> - about our policies,
 *       and about this customer - independent of any particular conversation?"</li>
 * </ul>
 *
 * <p>The demo that proves the new layer is doing something Stage 3 couldn't: a policy question
 * ("can I return this unopened item?") that a brand-new customer with zero history can get
 * answered correctly, and a customer fact ("I have a latex allergy, please flag it") that gets
 * applied later in the same conversation without ever being repeated.
 */
@Component("stage4")
public class FullMemorySupportAgent implements SupportAgent, ForgettableAgent {

    private static final int MAX_MESSAGES = 20;

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;

    public FullMemorySupportAgent(ChatClient.Builder builder,
                                   WireTapAdvisor wireTap,
                                   EpisodicMemoryAdvisor episodicMemory,
                                   SemanticMemoryAdvisor semanticMemory,
                                   OrderCatalog catalog,
                                   RedisChatMemoryRepository redisRepository,
                                   @Value("classpath:prompts/support-system.st") Resource systemPrompt) throws IOException {

        String system = asString(systemPrompt).replace("{orders}", catalog.asPromptTable());

        this.chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(redisRepository)
                .maxMessages(MAX_MESSAGES)
                .build();

        this.chatClient = builder
                .defaultSystem(system)
                // Order (low to high): session history, then episodic recall, then semantic
                // recall, then wireTap prints the fully assembled prompt right before the model
                // sees it.
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        episodicMemory,
                        semanticMemory,
                        wireTap)
                .build();
    }

    @Override
    public String ask(String customerId, String conversationId, String question) {
        return chatClient.prompt()
                .advisors(a -> a
                        .param(ChatMemory.CONVERSATION_ID, conversationId)
                        .param(EpisodicMemoryAdvisor.CUSTOMER_ID, customerId)
                        .param(EpisodicMemoryAdvisor.SESSION_LABEL, conversationId))
                .user(question)
                .call()
                .content();
    }

    /** Clears this conversation's session state only - episodic and semantic memory are untouched. */
    @Override
    public void forget(String conversationId) {
        chatMemory.clear(conversationId);
    }

    @Override
    public String stageLabel() {
        return "Stage 4: session (Redis) + episodic (pgvector) + semantic memory (pgvector)";
    }

    private static String asString(Resource resource) throws IOException {
        return FileCopyUtils.copyToString(
                new java.io.InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8));
    }
}
