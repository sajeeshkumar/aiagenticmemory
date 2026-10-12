package com.example.aimemory.agent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Session state, made durable: one conversation's message list lives under one Redis key.
 *
 * <p>This is the Stage 2 payoff over an in-process {@code Map<String, List<Message>>}: kill the
 * JVM and restart it with the same conversation id, and the history is still there, because it
 * never lived in this process to begin with. {@link org.springframework.ai.chat.memory.MessageWindowChatMemory}
 * is the layer above this that trims the list to a fixed window and decides when to call us.
 *
 * <p>Spring AI ships an official Redis {@code ChatMemoryRepository}, but only from the 2.x line,
 * which needs a Spring Boot 4 upgrade. Writing the four methods below against plain
 * {@code StringRedisTemplate} keeps this PoC on Boot 3.5 and - as a bonus for a teaching PoC -
 * means you can read exactly what is stored instead of trusting a black box.
 */
@Repository
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final String KEY_PREFIX = "aimemory:session:";

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Duration ttl;

    public RedisChatMemoryRepository(StringRedisTemplate redis,
                                      ObjectMapper json,
                                      @Value("${app.memory.session-ttl:P1D}") Duration ttl) {
        this.redis = redis;
        this.json = json;
        this.ttl = ttl;
    }

    @Override
    public List<String> findConversationIds() {
        Set<String> keys = redis.keys(KEY_PREFIX + "*");
        return keys == null ? List.of() : keys.stream().map(k -> k.substring(KEY_PREFIX.length())).toList();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        String raw = redis.opsForValue().get(key(conversationId));
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<StoredMessage> stored = json.readValue(raw, new TypeReference<List<StoredMessage>>() { });
            return stored.stream().map(StoredMessage::toMessage).toList();
        }
        catch (Exception e) {
            throw new IllegalStateException("Corrupt chat memory for conversation " + conversationId, e);
        }
    }

    /**
     * {@link org.springframework.ai.chat.memory.MessageWindowChatMemory} always passes the full,
     * already-trimmed window here, so a plain overwrite (not an append) is correct.
     */
    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        List<StoredMessage> stored = messages.stream().map(StoredMessage::fromMessage).toList();
        try {
            redis.opsForValue().set(key(conversationId), json.writeValueAsString(stored), ttl);
        }
        catch (Exception e) {
            throw new IllegalStateException("Could not persist chat memory for conversation " + conversationId, e);
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        redis.delete(key(conversationId));
    }

    private static String key(String conversationId) {
        return KEY_PREFIX + conversationId;
    }

    /** Plain JSON shape stored in Redis - deliberately simpler than Spring AI's own Message types. */
    record StoredMessage(String role, String text) {

        @JsonCreator
        StoredMessage(@JsonProperty("role") String role, @JsonProperty("text") String text) {
            this.role = role;
            this.text = text;
        }

        static StoredMessage fromMessage(Message message) {
            return new StoredMessage(message.getMessageType().getValue(), message.getText());
        }

        Message toMessage() {
            MessageType type = MessageType.fromValue(role);
            return switch (type) {
                case SYSTEM -> new SystemMessage(text);
                case ASSISTANT -> new AssistantMessage(text);
                case USER -> new UserMessage(text);
                // TOOL messages don't appear until Stage 5 adds function calling; fall back safely.
                default -> new UserMessage(text);
            };
        }
    }
}
