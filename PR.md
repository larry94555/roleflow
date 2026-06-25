# Add conversation memory with Claude Code-style auto-compaction

## Summary

RoleFlow now keeps a **conversation memory** so each prompt has the context of the ones before it, and
**compacts** that memory when it grows too large to fit the model's context window — the same approach
Claude Code uses to auto-compact a long session. This guarantees the required invariant: **the remembered
conversation plus the new prompt are always smaller than `MAX_TOKEN_SIZE`** (8192 for now; exposed as
`memory.max-tokens` and intended to become more flexible later).

A session lasts for the lifetime of the process; restarting the app starts a fresh session (persistence
is left for a future change, as discussed). The terminal and the web page share one session.

## How it works

Before each request, the memory estimates `system prompt + running summary + retained turns + new prompt`
and compares it to `memory.max-tokens` minus the reserved response space. When it would overflow:

1. The **oldest** turns are evicted first (so the **most recent** turns are always kept verbatim).
2. The evicted turns are folded into a running **summary** via the model, preserving goals, decisions,
   names, numbers, and constraints while dropping verbatim detail.
3. The summary is carried forward as `Summary of earlier conversation: …` and re-folded on each later
   compaction, so context accumulates instead of being discarded.

If a single prompt is itself larger than the budget, memory is cleared and the prompt is sent on its own
as a best effort.

## Changes

**New:**
- `Message` — a chat message (role + content) with a `/v1/chat/completions` wire mapping.
- `TokenEstimator` — dependency-free local token-size estimation (`memory.chars-per-token`, ~4/char).
- `Summarizer` (interface) + `LlmSummarizer` — fold older turns into a running summary; best-effort
  (keeps the prior summary if the model call fails, so the invariant holds).
- `ConversationMemory` — session state + the compaction algorithm; synchronized because the web page and
  terminal share it.
- `ConversationService` — assembles the budget-fitting request, calls the model, records the exchange.

**Updated:**
- `LlamaClient` — added `chat(messages, …)` for prebuilt message lists; `ask(…)` now delegates to it
  (same wire format, existing behavior unchanged).
- `AskController` and `TerminalPromptRunner` — now route through `ConversationService`, so both web and
  terminal prompts use the shared memory. The system-prompt default moved into `ConversationService`.
- `application.properties` — added `memory.max-tokens=8192`, `memory.chars-per-token=4`,
  `memory.summary-max-tokens=1024`.
- `README.md` — new "Conversation memory and compaction" section (what it is, an example, why compaction
  is needed, and exactly what happens when memory is compacted), plus updated config table and layout.

The `/ask` request/response shape is unchanged. `LlamaServerManager` (llama-server launch behavior) is
untouched.

## Testing

`mvnw test` — **41 tests, all passing** (up from 23), fully offline.

| Test class                  | Coverage                                                                       |
|-----------------------------|--------------------------------------------------------------------------------|
| `ConversationMemoryTest`    | No-op when within budget; compaction evicts oldest + keeps recent; **budget invariant holds**; running summary carried forward; oversized prompt clears memory; response reserve respected |
| `ConversationServiceTest`   | Default vs. per-call system prompt; **later prompts carry earlier turns**; blank-prompt rejection |
| `LlmSummarizerTest`         | Transcript assembly (prior summary + messages), temperature 0, graceful fallback on model failure |
| `TokenEstimatorTest`        | Chars-per-token estimation + per-message overhead                              |
| `LlamaClientTest`           | Added `chat()` round-trip + empty-message guard (existing `ask`/body tests kept) |
| `AskControllerTest` / `TerminalPromptRunnerTest` | Updated to the `ConversationService` seam                 |
| `LlamaServerManagerTest` / `RoleFlowApplicationTests` | Unchanged behavior / full context wiring         |

The memory tests use a fake summarizer and a deterministic token estimator, so they assert the budget
invariant directly without a running model.

## Try it

```
RoleFlow> My name is Ada and I like sailing.
Nice to meet you, Ada!
RoleFlow> What's my name and hobby?
Your name is Ada and you enjoy sailing.
```

After a long conversation, older turns are paraphrased from the summary while recent turns stay exact —
that's compaction working.
