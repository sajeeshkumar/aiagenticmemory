package com.example.aimemory.agent;

/**
 * Common shape for every stage of the agent, so the CLI can swap one stage for another without
 * caring how (or whether) it remembers anything.
 */
public interface SupportAgent {

    /**
     * @param customerId   identifies the person asking, independent of which session they're in.
     *                      Only episodic memory (Stage 3+) needs this - it's what lets a memory be
     *                      scoped to "this customer, any session" rather than "this session only".
     *                      Earlier stages are free to ignore it, same as Stage 1 ignores
     *                      {@code conversationId}: a stage that doesn't track identity simply can't
     *                      scope anything to a person, which is itself worth seeing.
     * @param conversationId identifies the session this question belongs to. A stage with no
     *                        session state (Stage 1) is free to ignore it entirely - that's the
     *                        whole point of calling it out in the signature.
     */
    String ask(String customerId, String conversationId, String question);

    /** Short label the CLI prints in its banner, e.g. "no memory", "session state (Redis)". */
    String stageLabel();
}
