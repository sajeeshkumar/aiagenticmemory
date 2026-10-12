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
 * Stage 3 agent: <b>session state (Stage 2) plus episodic memory, held in pgvector.</b>
 *
 * <p>This stage doesn't replace Stage 2's Redis session memory, it adds to it - a realistic
 * agent needs both. {@link MessageChatMemoryAdvisor} still gives perfect recall <i>inside</i> one
 * conversation id. {@link EpisodicMemoryAdvisor} is the new layer: it recalls - by meaning, not by
 * conversation id - things this same customer said in a <i>different, earlier</i> session.
 *
 * <p>The demo that proves it: tell it something in session A, start a brand new session B (a
 * fresh conversation id - Stage 2's memory would see nothing), and ask about it again. Stage 3
 * answers anyway, because {@link EpisodicMemoryAdvisor} searched pgvector for this customer's
 * past episodes by similarity and found the one from session A.
 */
@Component("stage3")
public class EpisodicSupportAgent implements SupportAgent, ForgettableAgent {

    private static final int MAX_MESSAGES = 20;

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;

    public EpisodicSupportAgent(ChatClient.Builder builder,
                                 WireTapAdvisor wireTap,
                                 EpisodicMemoryAdvisor episodicMemory,
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
                // Order (low to high): session memory loads this conversation's history first,
                // then episodic memory layers in context from the customer's other sessions,
                // then wireTap prints the fully assembled prompt right before the model sees it.
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build(), episodicMemory, wireTap)
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

    /** Clears this conversation's session state only - the customer's episodic memory is untouched. */
    @Override
    public void forget(String conversationId) {
        chatMemory.clear(conversationId);
    }

    @Override
    public String stageLabel() {
        return "Stage 3: session state (Redis) + episodic memory (pgvector)";
    }

    private static String asString(Resource resource) throws IOException {
        return FileCopyUtils.copyToString(
                new java.io.InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8));
    }
}
