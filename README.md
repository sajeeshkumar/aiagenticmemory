# Agentic Memory PoC — Spring AI + OpenRouter

A support agent for an online store, built up in stages so each kind of memory in an
agentic architecture can be seen doing its job — and seen *missing*.

| Stage | Memory | What it buys the agent |
|-------|--------|------------------------|
| 1 | *none* | nothing — every turn starts from zero |
| 2 | **session state** | "where is it?" resolves to the order discussed a turn ago |
| 3 | **episodic** | "what did I tell you last time?" — recall across *different* sessions |
| 4 | **semantic** | durable facts and policies, retrieved by meaning, not tied to one event |
| 5 (now) | **procedural** | the agent's own how-to: a procedure library, plus tools to act on it |

All five agents stay in the app at once — `SupportConsole` picks one with `--stage=1|2|3|4|5`
(default: the latest) — so you can run the same kind of conversation through each and watch
the difference directly.

## Stage 1: no memory

`StatelessSupportAgent` sends exactly one system message plus the current user message on
every call. There is no conversation id, no history read, no history write — so the order
number the customer gave a moment ago is gone.

## Stage 2: session state (Redis)

`SessionAwareSupportAgent` wraps the same call with Spring AI's `MessageChatMemoryAdvisor`,
backed by `MessageWindowChatMemory` (kept to the most recent 20 messages per conversation).
Before each call the advisor loads the conversation's prior turns; after each call it writes
the new turn back. "It" in "where is it?" now resolves, because the order number is sitting
right there in the message list the model actually sees.

The memory itself lives in Redis via `RedisChatMemoryRepository` — a hand-written
`ChatMemoryRepository` against plain `StringRedisTemplate`, one JSON array per conversation
id, with a 1-day TTL. Stop the JVM and start it again with the same `--session` id, and the
conversation picks up exactly where it left off, because the history never lived in that
process to begin with. (Spring AI does ship an official Redis repository, but only from the
2.x line, which needs a Spring Boot 4 upgrade — too big a jump for one dependency here.)

**What session state *can't* do:** start a different `--session` id - a different
conversation, like the customer closing the chat and opening a new one tomorrow - and it's
gone. That's the gap Stage 3 fills.

## Stage 3: episodic memory (pgvector)

`EpisodicSupportAgent` keeps everything Stage 2 has (session state is still real and still
useful) and adds `EpisodicMemoryAdvisor`: the same "load before the call, save after it"
shape as `MessageChatMemoryAdvisor`, but keyed by **meaning** and a **customer id** instead
of by conversation id.

- *After* every turn, the question+answer pair is embedded and stored as one "episode" in
  pgvector, tagged with `customer_id`.
- *Before* every turn, the advisor embeds the new question and runs a similarity search over
  that same customer's past episodes - from *any* session - and splices the best matches into
  the prompt as a `SystemMessage`, right before the current question.

Embeddings are computed locally via `spring-ai-starter-model-transformers` (the
`all-MiniLM-L6-v2` ONNX model, 384 dimensions, runs on CPU) — OpenRouter has no embedding
endpoint, so this had to happen somewhere free and local regardless of which chat model is
in use.

**The demo that actually proves it** (`EpisodicMemoryAdvisor.java`'s point in one line): it
spans *two different session ids* for the same customer. Session A mentions something — in
the shipped demo, an accessibility note ("I'm hard of hearing, please always write things
down") that appears nowhere in `OrderCatalog` and that no ordinary lookup could answer.
Session B is a brand-new conversation id with an empty Redis window; Stage 2 alone would
have started it from zero, same as Stage 1 did in the single-session demo. It gets the
accessibility note right anyway, because `EpisodicMemoryAdvisor` found session A's episode in
pgvector by similarity search, not by conversation id.

Episodes are scoped by `customer_id`, so one customer's episodes never leak into another's
retrieval - confirmed by running two different customers through the demo and checking the
raw `vector_store` table (`docker exec ... psql -U aimemory -d aimemory -c "SELECT
metadata->>'customer_id', content FROM vector_store;"`).

`WireTapAdvisor` still prints the real message list sent on each call, so you can watch the
episodic `SystemMessage` get spliced in right before the final user message — that list *is*
the agent's memory; every stage is a different strategy for filling it.

## Stage 4: semantic memory (pgvector, separate table)

`FullMemorySupportAgent` keeps everything Stage 3 has and adds `SemanticMemoryAdvisor`: the
same "load before, save after" shape again, but for **durable, curated facts** instead of
**automatically recorded events**. That distinction is made structural, not just asserted —
semantic memory lives in its own `semantic_memory` pgvector table (`VectorStoreConfig`),
completely separate from Stage 3's `vector_store`, so it's checkable with a plain SQL query
rather than a comment.

Semantic memory holds two different kinds of content:

- **Store policies** — four short documents (`policy-returns`, `policy-warranty`,
  `policy-shipping-delay`, `policy-backorder`), seeded once at startup by
  `SemanticKnowledgeSeeder`, shared by *every* customer, scoped `global`. They have nothing
  to do with any conversation — a brand-new customer with zero history gets them too.
- **Customer facts** — short, canonical statements captured only when the customer says
  something that sounds meant to be remembered going forward (a phrase-trigger heuristic in
  `SemanticMemoryAdvisor` — a real system would use an LLM call or a tool for this, which is
  Stage 5 territory), scoped to that `customer_id`.

Both are retrieved by one similarity search per turn, filtered to `scope == 'global' OR
customer_id == '<this customer>'`, and spliced into the prompt as a `SystemMessage` — same
mechanism as Stage 3's episodic context, just pointed at a different table with different
write rules.

**The demo that proves the two new things** (`FullMemorySupportAgent`'s point in one line):

1. *Part A* — a brand-new customer, zero history either way, asks a return-policy question.
   Stage 3 alone couldn't answer it (no episode exists yet, and `OrderCatalog` has no return
   policy); Stage 4 answers correctly from the globally-seeded policy, combined with the
   order's actual delivery date from `OrderCatalog`.
2. *Part B/C* — the customer states a latex allergy ("please flag anything with latex-based
   packaging"), then later in the *same* session asks an unrelated-sounding question ("anything
   about packaging I should double check?"). The allergy comes back anyway — proof that
   semantic retrieval works by meaning on short canonical facts, not by wording overlap with
   a literal past exchange the way episodic memory's transcripts do.

Check the separation directly:

```bash
docker exec aiagenticmemory-pgvector-1 psql -U aimemory -d aimemory -c \
  "SELECT id, metadata->>'scope', left(content,60) FROM semantic_memory;"
docker exec aiagenticmemory-pgvector-1 psql -U aimemory -d aimemory -c \
  "SELECT metadata->>'customer_id', left(content,60) FROM vector_store;"
```

The four `policy-*` rows show up with stable, readable ids (re-seeding is a plain upsert, so
restarting never duplicates them) and no `customer_id`; the customer's latex-allergy fact
shows up alongside them with one, and the episodic transcripts from `vector_store` are
untouched.

One implementation note worth keeping if you extend this further: Spring AI's own
`spring-ai-autoconfigure-vector-store-pgvector` guards its `VectorStore` bean with
`@ConditionalOnMissingBean(VectorStore.class)` — the moment a *second* `VectorStore` bean of
any name exists in the context (which Stage 4 introduces), it silently backs off and neither
store gets autoconfigured the way you'd expect. `VectorStoreConfig` defines both stores by
hand instead of fighting that.

## Stage 5: procedural memory (tools + a third pgvector table)

`ToolUsingSupportAgent` keeps everything Stage 4 has (session, episodic, semantic all still
run exactly as before) and adds the one thing no earlier stage had: the ability to actually
*do* something. Every stage up to now could only ever describe `OrderCatalog`; nothing a
conversation did ever changed it.

Procedural memory is split the same way the cognitive-science term is usually split — knowing
*how*, split from actually *doing*:

- **`ProcedureLibrary`** (`findProcedure` tool) is the "how" half: a small playbook of named,
  step-by-step procedures (`proc-return-unopened`, `proc-failed-delivery`,
  `proc-prolonged-backorder`), seeded by `ProcedureSeeder` into its own `procedure_memory`
  pgvector table — a third table, alongside Stage 3's `vector_store` and Stage 4's
  `semantic_memory`, each memory type still checkable on its own.
- **`OrderActionTools`** (`processRefund`, `rescheduleDelivery`, `applyBackorderStoreCredit`,
  `escalateToHuman`) is the "doing" half: real `@Tool` methods that mutate `OrderCatalog` for
  real — an order's status genuinely changes, not just the agent's description of it.

**The retrieval shape is deliberately different from Stages 3 and 4.** Episodic and semantic
memory are *advisors*: they inject context into the prompt automatically, before the model
even starts thinking, whether it ends up using it or not. Procedural memory is a *tool*: the
model has to recognize a situation and choose to call `findProcedure` itself, the same way a
person consults a runbook rather than having it read aloud regardless of need. The system
prompt (`support-system-tools.st`) tells it to do exactly that before acting on its own.

Because the chat model resolves the whole tool-call round trip internally, none of it shows
up in `WireTapAdvisor`'s printed prompt the way episodic/semantic context does. Instead,
`OrderActionTools` and `ProcedureLibrary` each print their own `[tool] ...` trace line when
called — a tool-call equivalent of the prompt wire-tap, so the mechanism stays visible instead
of disappearing inside the model call.

**The demo that proves it** doesn't ask you to trust the reply text — it prints
`OrderCatalog`'s real state for the affected orders before and after:

1. *Part A* — "please process the refund" for an unopened ORD-1002. `findProcedure` matches
   `return-unopened-electronics` *before* anything happens; `processRefund` then actually
   flips the order's status to `REFUNDED`.
2. *Part B* — a prolonged-backorder question about ORD-1003. `findProcedure` matches a
   *different* procedure by meaning; `applyBackorderStoreCredit` appends a real note.

The before/after `OrderCatalog` dump is the proof: ORD-1002 reads `REFUNDED` and ORD-1003
carries the store-credit note, changed by nothing but that tool call.

One thing worth knowing if you restart between runs: `OrderCatalog` is a plain in-memory bean,
reseeded from scratch on every JVM start — unlike the Redis- and pgvector-backed memories, it
was never meant to be real memory infrastructure, just a fake order-management system to act
on. A refund from a previous run won't still show `REFUNDED` after a restart; that's
`OrderCatalog` resetting, not procedural memory failing.

The procedure library itself, by contrast, *is* durable (same upsert-on-id pattern as Stage
4's policies) and is where a real version of this would grow: a human agent's novel
resolution to a new situation gets written back as a new procedure, so the next occurrence is
handled the same way automatically. That write path is out of scope here — this stage ships a
fixed, curated set, the same choice Stage 4 made for its policies — but the shape of how it
would plug in is the same `procedureStore.add(List.of(newProcedure))` call `ProcedureSeeder`
already uses.

## Run it

```bash
cp .env.example .env    # then paste your OpenRouter key into it
./run.sh --demo                       # stage 5 (default): procedure lookup + real tool actions
./run.sh --stage=4 --demo             # stage 4's policy + customer-fact recall demo, for contrast
./run.sh --stage=3 --demo             # stage 3's two-session episodic recall demo, for contrast
./run.sh --stage=2 --demo             # stage 2's single-session demo, for contrast
./run.sh --stage=1 --demo             # stage 1's single-session demo, for contrast
./run.sh                              # interactive REPL, stage 5, random customer+session ids
./run.sh --customer=cust-demo1 --session=sess-demo1   # pin ids so you can resume them later
```

`run.sh` starts Redis and pgvector for you via `docker compose` if Docker is running and
nothing already answers on their ports; otherwise point `REDIS_HOST`/`REDIS_PORT` and
`PGVECTOR_HOST`/`PGVECTOR_PORT`/`PGVECTOR_DB`/`PGVECTOR_USER`/`PGVECTOR_PASSWORD` at your own
instances (see `.env.example`).

**To see the Redis session-state payoff directly:** run `./run.sh --stage=2
--session=sess-demo1`, tell it about `ORD-1001`, then `Ctrl+C` to kill the process entirely.
Run it again with the same `--session` and ask "where is it?" with no process left alive in
between — it still resolves.

In interactive mode, type `forget` to clear the *current session's* memory on the spot (Stage
2+ only). It doesn't touch episodic or semantic memory — that's the point: forgetting a
conversation isn't the same as forgetting a customer.

### Stage 1 → 2 contrast (`--stage=1|2 --demo`)

1. "I need help with my order ORD-1001." → fine.
2. "Where is it right now?" → Stage 1 has to ask which order; Stage 2 resolves it.
3. "So when will it arrive?" → same split.
4. "When will ORD-1001 arrive?" → both fine again, because the customer repeated himself.

### Stage 3 demo (`--stage=3 --demo`)

Session A: tells the agent an accessibility preference.
Session A ends; session B starts with a brand-new conversation id (and the same customer id).
Session B: asks what was noted last time, then asks an ordinary order-status question.
Only episodic memory can answer the first question correctly; the second comes from the
regular catalog lookup either way.

### Stage 4 demo (`--stage=4 --demo`)

Part A: a brand-new customer asks a return-policy question (zero history of any kind).
Part B: the same customer states a durable fact (a latex allergy) in passing.
Part C: a later, differently-worded question in the *same* session depends on that fact.
Part A only works because the policy was seeded globally, not learned from conversation; part
C only works because semantic memory searches facts by meaning, not by wording overlap.

### Stage 5 demo (`--demo`, the default)

Prints `OrderCatalog`'s state for ORD-1002 and ORD-1003, then: part A asks for an unopened
return to be refunded; part B asks about a prolonged backorder. Prints the catalog's state
again afterward. Watch for the `[tool] findProcedure(...) -> matched '...'` trace line before
each action - a different procedure matched each time, by meaning - and the before/after diff:
ORD-1002 goes `DELIVERED` → `REFUNDED`, ORD-1003 gains a real store-credit note.

## Model

OpenRouter is OpenAI-wire-compatible, so `spring-ai-starter-model-openai` is the client;
only `base-url` changes. Default model is the free `nvidia/nemotron-3-super-120b-a12b:free`.
Override without touching code:

```bash
export OPENROUTER_MODEL=google/gemma-4-31b-it:free
```

Free-tier models are rate limited and occasionally busy; a 429 means wait or switch model —
other free, tool-calling-capable options on OpenRouter right now: `google/gemma-4-31b-it:free`,
`google/gemma-4-26b-a4b-it:free`, `thinkingmachines/inkling:free`.
