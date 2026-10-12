package com.example.aimemory.agent;

/** Implemented by any stage that keeps session state it's possible to clear on demand. */
public interface ForgettableAgent {

    /** Clears session state for one conversation id. Does not touch any longer-lived memory
     *  (episodic, semantic, procedural) a later stage might layer on top. */
    void forget(String conversationId);
}
