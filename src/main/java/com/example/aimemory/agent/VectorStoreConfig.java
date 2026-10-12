package com.example.aimemory.agent;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Stage 4 needs a second pgvector table, kept deliberately separate from Stage 3's - so both
 * stores are defined here, explicitly.
 *
 * <p>Episodic memory ({@code vector_store}) is an ever-growing, automatically-written log of
 * individual turns. Semantic memory ({@code semantic_memory}) is a small, curated set of durable
 * facts - store policies seeded once at startup, customer facts captured only when explicitly
 * stated. Giving them separate tables makes that architectural distinction checkable with a
 * plain SQL query, not just a comment.
 *
 * <p>Both are built by hand rather than left to {@code spring-ai-autoconfigure-vector-store-
 * pgvector}: that autoconfiguration's own {@code vectorStore} bean carries
 * {@code @ConditionalOnMissingBean(VectorStore.class)}, so the moment a second {@code VectorStore}
 * bean of any name exists in the context, it backs off and <i>neither</i> store gets created the
 * way you'd expect. Defining both explicitly sidesteps that rather than fighting it.
 */
@Configuration
public class VectorStoreConfig {

    public static final String EPISODIC_STORE = "vectorStore";
    public static final String SEMANTIC_STORE = "semanticVectorStore";
    public static final String PROCEDURE_STORE = "procedureVectorStore";

    /** Stage 3's store. Table name, id type (UUID), index and distance type are all left at
     *  PgVectorStore's own defaults - only schema initialization needs turning on explicitly. */
    @Bean(EPISODIC_STORE)
    public PgVectorStore episodicVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .initializeSchema(true)
                .build();
    }

    /**
     * {@code idType(TEXT)} (episodic stays on the default UUID) is what lets
     * {@link SemanticKnowledgeSeeder} use short, readable, stable ids like
     * {@code "policy-returns"} and re-seed on every startup as a plain upsert.
     */
    @Bean(SEMANTIC_STORE)
    public PgVectorStore semanticVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName("semantic_memory")
                .idType(PgVectorStore.PgIdType.TEXT)
                .initializeSchema(true)
                .build();
    }

    /**
     * Stage 5's playbook of named, step-by-step procedures (SOPs) - "how to handle this kind of
     * situation", as opposed to semantic memory's "what is true". Retrieved differently, too: not
     * auto-injected into the prompt by an advisor, but looked up on demand by
     * {@link ProcedureLibrary}'s {@code findProcedure} tool, which the model calls explicitly
     * when it recognizes a situation - procedural memory is invoked, not recalled into context.
     */
    @Bean(PROCEDURE_STORE)
    public PgVectorStore procedureVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName("procedure_memory")
                .idType(PgVectorStore.PgIdType.TEXT)
                .initializeSchema(true)
                .build();
    }
}
