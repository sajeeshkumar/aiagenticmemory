package com.example.aimemory.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

import com.example.aimemory.domain.OrderCatalog;

/**
 * Stage 2 agent: <b>session state, held in Redis.</b>
 *
 * <p>Every call still builds one {@code Prompt}, same as Stage 1 - but now
 * {@link MessageChatMemoryAdvisor} reads the conversation's prior turns out of
 * {@link #chatMemory} before the call and writes the new turn back after it. "It" in
 * "where is it?" now resolves, because the order number from a turn ago is sitting right there
 * in the message list the model actually sees (watch {@link WireTapAdvisor}'s output grow).
 *
 * <p>What makes this different from an in-process {@code Map}: {@link #chatMemory} is backed by
 * {@link RedisChatMemoryRepository}, so the history survives this JVM exiting. Run the demo twice
 * with the same {@code --session} id - the second run picks up where the first left off.
 */
@Component("stage2")
public class SessionAwareSupportAgent implements SupportAgent, ForgettableAgent {

    /** Keep only the most recent N messages per conversation, so Redis doesn't grow unbounded. */
    private static final int MAX_MESSAGES = 20;

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;

    public SessionAwareSupportAgent(ChatClient.Builder builder,
                                     WireTapAdvisor wireTap,
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
                // Order matters: the memory advisor's default order is far lower than wireTap's,
                // so history is already loaded into the prompt by the time wireTap prints it.
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build(), wireTap)
                .build();
    }

    /** {@code customerId} is accepted only to satisfy {@link SupportAgent} - this stage has no concept of identity, only of conversation id. */
    @Override
    public String ask(String customerId, String conversationId, String question) {
        return chatClient.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .user(question)
                .call()
                .content();
    }

    /** Lets the CLI offer a "forget this session" command without exposing the Redis repository. */
    @Override
    public void forget(String conversationId) {
        chatMemory.clear(conversationId);
    }

    @Override
    public String stageLabel() {
        return "Stage 2: session state (Redis)";
    }

    private static String asString(Resource resource) throws IOException {
        return FileCopyUtils.copyToString(
                new java.io.InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8));
    }
}
