package com.example.aimemory.cli;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.example.aimemory.agent.ForgettableAgent;
import com.example.aimemory.agent.SupportAgent;
import com.example.aimemory.domain.OrderCatalog;

/**
 * Terminal front end for the support agent.
 *
 * <p>Options:
 * <ul>
 *   <li>{@code --stage=1|2|3|4|5} - which agent to talk to (default: the latest).</li>
 *   <li>{@code --customer=<id>} - who's asking. Only Stage 3+ uses this; it's what scopes
 *       episodic and semantic customer facts to one person across every session they've ever
 *       had.</li>
 *   <li>{@code --session=<id>} - the conversation id. Omit it and one is generated and printed;
 *       pass the same id again (even after restarting the process) to prove Stage 2+'s session
 *       state lives in Redis, not in this JVM.</li>
 *   <li>{@code --demo} - run the scripted conversation instead of reading stdin.</li>
 * </ul>
 */
@Component
public class SupportConsole implements ApplicationRunner {

    /** A conversation that only works if the agent keeps session state. Stage 1 fails turn 2 on. */
    private static final List<String> SESSION_DEMO_SCRIPT = List.of(
            "Hi, I need help with my order ORD-1001.",
            "Where is it right now?",
            "So when will it arrive?",
            "When will ORD-1001 arrive?");

    private final Map<String, SupportAgent> agentsByStage;
    private final OrderCatalog catalog;

    public SupportConsole(Map<String, SupportAgent> agentsByStage, OrderCatalog catalog) {
        this.agentsByStage = agentsByStage;
        this.catalog = catalog;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // Bump this default each time a new stage is added; agentsByStage.size() would work too
        // but relies on no other SupportAgent beans ever being registered, so spell it out.
        String stage = firstValue(args, "stage").orElse("5");
        SupportAgent agent = resolveAgent(stage);
        String customerId = firstValue(args, "customer").orElseGet(() -> "cust-" + shortId());
        String sessionId = firstValue(args, "session").orElseGet(() -> "sess-" + shortId());
        banner(agent, customerId, sessionId);

        if (args.containsOption("demo")) {
            switch (stage) {
                case "3" -> runEpisodicDemo(agent, customerId, sessionId);
                case "4" -> runSemanticDemo(agent, customerId, sessionId);
                case "5" -> runProceduralDemo(agent, customerId, sessionId);
                default -> runSessionDemo(agent, customerId, sessionId);
            }
        }
        else {
            runInteractive(agent, customerId, sessionId);
        }
    }

    private SupportAgent resolveAgent(String stage) {
        SupportAgent agent = agentsByStage.get("stage" + stage);
        if (agent == null) {
            throw new IllegalArgumentException("Unknown --stage=" + stage + ". Known stages: " + agentsByStage.keySet());
        }
        return agent;
    }

    private Optional<String> firstValue(ApplicationArguments args, String option) {
        List<String> values = args.getOptionValues(option);
        return (values == null || values.isEmpty()) ? Optional.empty() : Optional.of(values.get(0));
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void banner(SupportAgent agent, String customerId, String sessionId) {
        System.out.printf("""

                ================================================================
                 Agentic memory PoC - %s
                ================================================================
                 customer id: %s   session id: %s
                 Re-run with --customer=%s --session=%s (even after restarting
                 this process) to continue exactly where this left off.
                ----------------------------------------------------------------%n""",
                agent.stageLabel(), customerId, sessionId, customerId, sessionId);
    }

    private void runSessionDemo(SupportAgent agent, String customerId, String sessionId) {
        int turn = 0;
        for (String question : SESSION_DEMO_SCRIPT) {
            System.out.printf("%n[turn %d] customer> %s%n", ++turn, question);
            System.out.printf("agent> %s%n", agent.ask(customerId, sessionId, question));
        }
        System.out.println("""

                ----------------------------------------------------------------
                 Stage 1: turn 1 works, turns 2-3 fail ("it" resolves to nothing),
                 turn 4 works again only because the customer repeated himself.
                 Stage 2: all four turns work, because "it" is resolved from the
                 message history MessageChatMemoryAdvisor loaded out of Redis.
                ----------------------------------------------------------------""");
    }

    /**
     * Stage 3's demo spans two separate sessions on purpose. Session B is a brand new
     * conversation id - Stage 2's Redis memory would start it from zero, same as Stage 1 did in
     * the single-session demo. The only reason the follow-up in Session B works is
     * {@code EpisodicMemoryAdvisor} finding Session A's episode in pgvector by meaning.
     */
    private void runEpisodicDemo(SupportAgent agent, String customerId, String sessionId) {
        String sessionA = sessionId + "-a";
        String sessionB = sessionId + "-b";

        // Deliberately not anything OrderCatalog could ever answer, so the only way session B
        // can get this right is by actually recalling session A - no catalog lookup could fake it.
        System.out.println("\n--- session A (first contact) ---");
        ask(agent, customerId, sessionA,
                "Hi, quick note for my file before we start: I'm hard of hearing, so please always "
                        + "give me written steps instead of asking me to call support.");

        System.out.println("\n--- session A ends here. new process, new day, brand new session id: " + sessionB + " ---");

        System.out.println("\n--- session B (customer comes back) ---");
        ask(agent, customerId, sessionB,
                "Hi again - quick check, what accessibility note did I ask you to keep on file last time?");
        ask(agent, customerId, sessionB,
                "Thanks. Separately, what's the status of ORD-1003?");

        System.out.println("""

                ----------------------------------------------------------------
                 Session B has no Redis history at all - it's a fresh conversation
                 id with an empty window, and nothing about "hard of hearing" is in
                 OrderCatalog for the model to fall back on. The first reply could
                 only have come from EpisodicMemoryAdvisor finding session A's
                 episode in pgvector BY MEANING. The second reply, about ORD-1003,
                 used the ordinary order lookup - no episode mentioned it.
                ----------------------------------------------------------------""");
    }

    /**
     * Stage 4's demo stays in one session on purpose - cross-session recall is Stage 3's trick,
     * already proven. This one shows the two things semantic memory adds on top of that:
     * <ol>
     *   <li>a policy question a brand-new customer with zero history can get right, because the
     *       policy was seeded once and is shared by everyone ({@link SemanticKnowledgeSeeder}),
     *       not learned from any conversation;</li>
     *   <li>a customer fact that, once stated, gets applied later in the <i>same</i> session
     *       without being repeated - and without needing wording similar enough for episodic
     *       memory's own similarity search to have caught it.</li>
     * </ol>
     */
    private void runSemanticDemo(SupportAgent agent, String customerId, String sessionId) {
        System.out.println("\n--- part A: a policy question, zero history, brand new customer ---");
        ask(agent, customerId, sessionId,
                "I'd like to return my Breville Bambino espresso machine from ORD-1002 - it's still "
                        + "factory sealed, never opened.");

        System.out.println("\n--- part B: state a durable fact ---");
        ask(agent, customerId, sessionId,
                "Please make a note on my file: I have a latex allergy, so flag anything with "
                        + "latex-based packaging or grips.");

        System.out.println("\n--- part C: a later, differently-worded question that depends on it ---");
        ask(agent, customerId, sessionId,
                "I'm thinking about ordering the Asics Novablast 5 again, like ORD-1003 - anything "
                        + "about packaging I should double check given what's on my file?");

        System.out.println("""

                ----------------------------------------------------------------
                 Part A: answered from policy-returns, seeded once at startup and
                 shared by every customer - nothing about it came from this or any
                 other conversation. Stage 3 alone (zero history either way) could
                 not have answered this; OrderCatalog alone has no return policy.
                 Part C: the latex allergy from part B came back even though "what
                 about packaging" doesn't closely resemble "please flag latex" -
                 SemanticMemoryAdvisor stores facts as short canonical statements
                 and searches them by meaning, independent of episodic memory.
                ----------------------------------------------------------------""");
    }

    /**
     * Stage 5's demo doesn't ask you to trust the reply text - it prints {@code OrderCatalog}'s
     * real state for the affected orders before and after, so the proof is in the data: every
     * earlier stage could only ever describe {@code OrderCatalog}, never change it.
     */
    private void runProceduralDemo(SupportAgent agent, String customerId, String sessionId) {
        System.out.println("\n--- before: ORD-1002 and ORD-1003 in OrderCatalog ---");
        printOrder("ORD-1002");
        printOrder("ORD-1003");

        System.out.println("\n--- part A: a return that should trigger a known procedure ---");
        ask(agent, customerId, sessionId,
                "I'd like to return my Breville Bambino espresso machine from ORD-1002 - it's still "
                        + "factory sealed, never opened. Please process the refund.");

        System.out.println("\n--- part B: a different situation, a different procedure ---");
        ask(agent, customerId, sessionId,
                "Separately - it's been over two weeks since you told me ORD-1003 was backordered. "
                        + "What can you do about that?");

        System.out.println("\n--- after: ORD-1002 and ORD-1003 in OrderCatalog ---");
        printOrder("ORD-1002");
        printOrder("ORD-1003");

        System.out.println("""

                ----------------------------------------------------------------
                 Watch the "[tool] ..." trace lines above the replies, not just
                 the replies themselves: findProcedure matched a named procedure
                 BEFORE any action was taken, in both parts - a different one
                 each time, picked by meaning, not by keyword. Then an action
                 tool actually ran. OrderCatalog's state above proves it: ORD-1002
                 is now REFUNDED and ORD-1003 carries a real store-credit note -
                 neither changed by anything other than that tool call. No
                 earlier stage could have done this; they could only talk.
                ----------------------------------------------------------------""");
    }

    private void printOrder(String orderId) {
        catalog.find(orderId).ifPresentOrElse(
                order -> System.out.println("  " + order.asLine()),
                () -> System.out.println("  " + orderId + ": not found"));
    }

    private void ask(SupportAgent agent, String customerId, String sessionId, String question) {
        System.out.printf("%ncustomer> %s%n", question);
        System.out.printf("agent> %s%n", agent.ask(customerId, sessionId, question));
    }

    private void runInteractive(SupportAgent agent, String customerId, String sessionId) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        int turn = 0;
        while (true) {
            System.out.printf("%n[turn %d] customer> ", turn + 1);
            System.out.flush();
            String line = in.readLine();
            if (line == null || line.isBlank() || "exit".equalsIgnoreCase(line.trim())
                    || "quit".equalsIgnoreCase(line.trim())) {
                System.out.println("bye.");
                return;
            }
            String trimmed = line.trim();
            if ("forget".equalsIgnoreCase(trimmed)) {
                forget(agent, sessionId);
                continue;
            }
            turn++;
            System.out.printf("agent> %s%n", agent.ask(customerId, sessionId, trimmed));
        }
    }

    private void forget(SupportAgent agent, String sessionId) {
        if (agent instanceof ForgettableAgent forgettable) {
            forgettable.forget(sessionId);
            System.out.println("(session state cleared - longer-lived memory, if this stage has any, is untouched)");
        }
        else {
            System.out.println("(this stage has no session memory to forget)");
        }
    }
}
