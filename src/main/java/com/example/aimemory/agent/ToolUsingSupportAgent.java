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
 * Stage 5 agent: Stage 4's full stack plus procedural memory - {@link ProcedureLibrary}'s
 * {@code findProcedure} tool and {@link OrderActionTools}'s action tools.
 *
 * <p>Every earlier stage could only ever answer <i>about</i> {@code OrderCatalog}; nothing
 * changed as a result of a conversation. This stage can act: a correctly-followed procedure
 * ends with a real tool call that mutates an order, which is why its demo prints the catalog's
 * state before and after - the proof isn't in the reply text, it's in the data.
 *
 * <p>Note what's deliberately re-used rather than rebuilt: session state, episodic recall and
 * semantic recall all still run exactly as in Stage 4. Procedural memory is additive, not a
 * replacement - a real agent needs to remember the conversation, the customer, the policies,
 * <i>and</i> how to actually do something about all of it.
 */
@Component("stage5")
public class ToolUsingSupportAgent implements SupportAgent, ForgettableAgent {

    private static final int MAX_MESSAGES = 20;

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;

    public ToolUsingSupportAgent(ChatClient.Builder builder,
                                  WireTapAdvisor wireTap,
                                  EpisodicMemoryAdvisor episodicMemory,
                                  SemanticMemoryAdvisor semanticMemory,
                                  OrderActionTools orderActionTools,
                                  ProcedureLibrary procedureLibrary,
                                  OrderCatalog catalog,
                                  RedisChatMemoryRepository redisRepository,
                                  @Value("classpath:prompts/support-system-tools.st") Resource systemPrompt) throws IOException {

        String system = asString(systemPrompt).replace("{orders}", catalog.asPromptTable());

        this.chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(redisRepository)
                .maxMessages(MAX_MESSAGES)
                .build();

        this.chatClient = builder
                .defaultSystem(system)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        episodicMemory,
                        semanticMemory,
                        wireTap)
                // Tools are registered separately from advisors: advisors decide what the model
                // already knows before it starts; tools are capabilities it can choose to use
                // mid-thought. Spring AI resolves the whole tool-call round trip internally, so
                // none of it shows up in WireTapAdvisor's printed prompt - watch stdout for the
                // "[tool] ..." trace lines from OrderActionTools and ProcedureLibrary instead.
                .defaultTools(orderActionTools, procedureLibrary)
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

    /** Clears this conversation's session state only - episodic, semantic and procedural memory are untouched. */
    @Override
    public void forget(String conversationId) {
        chatMemory.clear(conversationId);
    }

    @Override
    public String stageLabel() {
        return "Stage 5: session + episodic + semantic + procedural memory (tools)";
    }

    private static String asString(Resource resource) throws IOException {
        return FileCopyUtils.copyToString(
                new java.io.InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8));
    }
}
