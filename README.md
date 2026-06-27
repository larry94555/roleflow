# RoleFlow

A minimal Spring Boot application that launches a local [`llama-server`](https://github.com/ggml-org/llama.cpp)
and exposes a single REST endpoint, `POST /ask`, for sending a prompt and getting the model's reply.

There are two ways to send a prompt:

1. **From the terminal** — the running application reads prompts from standard input and prints the reply.
2. **From a web page** — open `http://localhost:8080/` in a browser, type a prompt, and see the response.

Both share a single **conversation memory**, so each prompt has the context of the ones before it. When
that memory grows too large to fit the model's context window, it is **compacted** automatically (the way
Claude Code auto-compacts a long session) — see [Conversation memory](#conversation-memory-and-compaction).

Every prompt is processed by a **role workflow** defined in [`config/roleflow.active`](config/roleflow.active):
the prompt is classified, requests are clarified, and a **goal file** and **plan file** are produced for
real requests. This means a single prompt usually results in **several** calls to `llama-server` — one per
role. See [Role workflow](#role-workflow) below and [CURRENT_PROCESS.md](CURRENT_PROCESS.md) for the full
flow.

Every prompt is also **audited**: each role that runs, the prompt and system prompt used, the model's
response, and how the transition was handled are recorded to a log file (follow it with `tail -f`) and to
a live **audit web page**. See [Audit and logging](#audit-and-logging) and
[AuditInstructions.md](AuditInstructions.md).

The application does exactly these things and nothing more:

- starts and supervises `llama-server` (same launch behavior as goalmaker),
- accepts prompts typed at the terminal,
- serves a simple web page that submits prompts and shows the response,
- remembers the conversation and compacts it when it gets too big,
- drives each prompt through the role workflow in `roleflow.active`,
- records an audit trail of every role step (log file + live web page),
- exposes an internal **tool registry** (MCP) with a `web_search` tool — see
  [Tool registry](#tool-registry) and [Tool_Registry.md](Tool_Registry.md),
- exposes an internal **skill registry** that injects domain guidance (e.g. a `mathematics` skill) into a
  role's prompt when the run's topics call for it — see [Skill_Registry.md](Skill_Registry.md).

---

## Requirements

- **Java 17 or newer** on your `PATH`.
- **`llama-server`** (from llama.cpp) on your `PATH`, or set `llama.binary` to its full path (see
  [Configuration](#configuration)). On the first run, `llama-server` downloads the model
  (`Qwen/Qwen2.5-3B-Instruct-GGUF:Q4_K_M` by default), which can take a few minutes.
- Maven is **not** required — the bundled wrapper (`mvnw` / `mvnw.cmd`) uses a system Maven if present
  and otherwise downloads a pinned copy automatically.

---

## How to start the application

From the project directory (`c:\Users\larry\github\RoleFlow`):

**Windows (recommended):**

```bat
run.bat
```

**Any platform, using the Maven wrapper directly:**

```bash
./mvnw spring-boot:run        # macOS / Linux / Git Bash
mvnw.cmd spring-boot:run      # Windows
```

On startup the app launches `llama-server` and waits until it reports healthy. The first launch can be
slow while the model downloads — progress is written to `llama-server.log` in the project directory.
Once you see `RoleFlow terminal ready.` and Spring's `Started RoleFlowApplication`, it is ready.

The web server listens on **port 8080**; `llama-server` listens on **port 8081**.

---

## Option 1 — Send a prompt from the terminal

While the application is running, the terminal where you started it shows a prompt:

```
RoleFlow terminal ready. Type a prompt and press Enter (type 'exit' or 'quit' to stop).
RoleFlow> What is the capital of France?
The capital of France is Paris.
RoleFlow>
```

Type your prompt, press **Enter**, and the reply is printed. Keep entering prompts as many times as
you like. Typing `exit` or `quit` stops the terminal reader (the application keeps running so the web
page and REST endpoint stay available).

### Sending a one-off prompt with `ask.bat`

If you prefer not to use the interactive prompt, `ask.bat` posts a single prompt to the REST endpoint
from any terminal while the app is running:

```bat
ask.bat "Write a haiku about the sea"
ask.bat "Translate to French: good morning" "You are a professional translator."
```

The first argument is the user prompt; the optional second argument is a system prompt.

---

## Option 2 — Send a prompt from the web page

While the application is running, open:

```
http://localhost:8080/
```

Type your **prompt** and click **Send** (or press **Ctrl+Enter**). The model's response appears below, and
each prompt is added to the conversation transcript on the page.

When you click **Send**, a **"🔎 View audit trail for this prompt ↗"** link appears and the audit page
opens in a new tab, so you can watch each role run live as the prompt is processed. See
[Audit and logging](#audit-and-logging).

---

## The REST API

Both the terminal and the web page are thin clients over one endpoint.

### `POST /ask`

Request body (JSON):

| Field         | Type    | Required | Description                                                        |
|---------------|---------|----------|--------------------------------------------------------------------|
| `prompt`      | string  | yes      | The user prompt.                                                   |
| `system`      | string  | no       | System prompt. Falls back to `roleflow.system-prompt` if omitted.  |
| `maxTokens`   | integer | no       | Max tokens to generate. Falls back to `prompt.max-tokens`.         |
| `temperature` | number  | no       | Sampling temperature. Omitted from the request when not provided.  |

Response body (JSON): `{ "response": "..." }`, or `{ "error": "..." }` with HTTP 400 for a bad request.

Example with `curl`:

```bash
curl -s http://localhost:8080/ask \
  -H "Content-Type: application/json" \
  -d '{"prompt":"Name three primary colors.","system":"Answer in one line."}'
```

---

## Conversation memory and compaction

RoleFlow keeps a **session memory**: every prompt is sent to the model together with the earlier prompts
and replies in the session, so the conversation has continuity.

```
RoleFlow> My name is Ada and I like sailing.
Nice to meet you, Ada!
RoleFlow> What's my name and hobby?
Your name is Ada and you enjoy sailing.   <-- answered from memory
```

The terminal and the web page share the **same** memory, so you can start a thread in one and continue it
in the other. A session lasts for as long as the application runs; **restarting the application starts a
fresh session** (persisting sessions across restarts is planned for later).

### Why compaction is needed

A model has a fixed **context window** — for the default model that window is **8192 tokens**
(`MAX_TOKEN_SIZE`). Everything sent in a request counts against it: the system prompt, the remembered
conversation, **and** the new prompt, plus room reserved for the reply. If the conversation just kept
growing, it would eventually overflow the window and the request would fail.

### What happens when memory gets too big

Before each request RoleFlow estimates the size of `system prompt + remembered conversation + new prompt`
and compares it against `MAX_TOKEN_SIZE` minus the space reserved for the response. If it would overflow,
memory is **compacted** the same way Claude Code auto-compacts a long session:

1. The **oldest** turns are removed from the live window first, so the **most recent** turns are always
   kept verbatim.
2. Those removed turns are folded into a running **summary** by asking the model to condense them —
   preserving goals, decisions, names, numbers, and constraints, while dropping verbatim detail.
3. The summary is carried forward and prepended to future requests as
   `Summary of earlier conversation: …`. Each later compaction folds the previous summary in with the
   newly removed turns, so context accumulates instead of being thrown away.

This guarantees the invariant the design requires: **the remembered conversation plus the new prompt are
always smaller than `MAX_TOKEN_SIZE`.** (If a single prompt is itself larger than the budget, memory is
cleared and the prompt is sent on its own as a best effort.)

In the terminal you can tell compaction happened because the next answer still "remembers" earlier facts
even after a long conversation, while older wording is paraphrased rather than quoted exactly.

### Tuning

`MAX_TOKEN_SIZE` is fixed at 8192 for now (it will become more flexible later) but is already exposed as
`memory.max-tokens`. Related options are in [Configuration](#configuration).

---

## Role workflow

Instead of answering each prompt with a single model call, RoleFlow drives the prompt through a sequence
of **roles** defined in [`config/roleflow.active`](config/roleflow.active). Each role is a system prompt
that wraps the (unchanging) user prompt so the model performs one job, then a **transition** decides which
role runs next. The same workflow is used for both terminal and web prompts.

The shipped workflow has ten roles:

1. **TopicAnalyzer** — identify the **topics** relevant to the prompt (e.g. mathematics, programming),
   at most three, or none for a very general prompt. The topics are recorded in the **audit trail** and
   surfaced in the final response.
2. **TopicContextBuilder** — for **every** identified topic, gather background details (its concerns and
   special interests) by running the `web_search` tool **once per topic**, so no topic is missed. This
   step is computed deterministically in code (no model call); the combined context is reused by later
   roles and each search is recorded in the **audit trail**. *(Skipped when there are no topics.)*
3. **SignalOrRequest** — classify the prompt as a *signal* (e.g. "Hello", "Thanks") or a *request*.
4. **SignalResponse** — for a signal: acknowledge or reply. *(ends here — no files)*
5. **HandleRequest** — for a request: make the success criteria clear, asking clarifying questions if
   needed (and pausing for your answer), or noting a cancellation.
6. **GoalBuilder** — write the **goal** (the criteria for success) to a file in `goals/`.
7. **PlanBuilder** — write a four-phase **plan** (Preparation, Action, Verification, Next steps) to a
   file in `goals/`. Phase 1 must call out the **assumptions and decision points** the request implies
   (e.g. the programming language for an app) — making them or adding steps to figure them out.
8. **PlanReviewer** — review the plan, using the **topic context** gathered by TopicContextBuilder
   to flag steps that contradict how the topic actually works; if a change is warranted it rewrites the
   plan file and re-reviews, otherwise it confirms no change. Its verdict is recorded in the **audit
   trail**.
9. **StepReviewer** — classify every step as *decision-point*, *request-for-information*, *action*, or
   *subgoal*, and record the classifications in the **audit trail**. This step is computed deterministically
   in code (no model call).
10. **ResponseBuilder** — confirm the goal and plan were created; the engine then appends the exact file
    locations as `file:///` URLs you can open from a browser.

So a single prompt produces **multiple `llama-server` calls — at least one per non-computed role.** A
signal is analysed for topics and then takes two more calls and writes nothing; a request runs through all
the roles and writes a **goal file** and a **plan file**. If a request is ambiguous, the Clarifier asks a question and waits; your next prompt answers it
and the flow resumes. Because every role call goes through the shared conversation memory, clarifying
questions and answers accumulate as context without disrupting the flow.

### Example (terminal)

```
RoleFlow> thanks!
You're welcome!                                  <-- signal: 2 calls, no files

RoleFlow> Set up a weekly backup of my notes folder
To make sure I get this right: should the backup run every week indefinitely,
and where should the copies be stored?           <-- request needed clarification (paused)
RoleFlow> Yes, weekly forever, store them on my external drive
Your request has been turned into a goal and a plan.
Goal file:  goals/goal_backup-notes_20260625-101500.md
Plan file:  goals/plan_backup-notes_20260625-101500.md   <-- request: goal + plan written
```

The file names start with a short, human-readable **prefix** summarizing the session's first prompt
(`backup-notes`), so goals and plans made on the same day are easy to tell apart. The prefix is kept
unique across sessions (a number is appended if it would repeat), is shared by a request's goal and plan,
and also appears in every audit log line — so `grep backup-notes_ audit.log` returns just that session.

For the complete flow — the JSON protocol each role uses, the exact transition rules, and a diagram — see
**[CURRENT_PROCESS.md](CURRENT_PROCESS.md)**.

### Turning the workflow off

If `config/roleflow.active` is removed or empty (or `roleflow.config` points nowhere), RoleFlow falls back
to **plain single-call mode**: each prompt is answered with one model call and no files are written.

---

## Audit and logging

Every prompt produces an **audit trail** — for each role that runs, the trail records the **user prompt**,
the **system prompt** used, the **model's response**, the **decision**, how the **transition** was handled,
and any **clarification iterations**. It is available two ways.

### As a log file (`tail -f`)

Audit events are written to **`audit.log`** (in the working directory) and echoed to the console. Follow
the trail live from another terminal:

```bash
tail -f audit.log
```

```
[audit run=20260625-101500-9f3a #2] ROLE_STARTED role=SignalOrRequest iteration=1
[audit run=20260625-101500-9f3a #3] MODEL_REQUEST role=SignalOrRequest
    system-prompt: You are RoleFlow ... Current role: SignalOrRequest ...
    user-prompt: Set up a weekly backup of my notes folder
[audit run=20260625-101500-9f3a #4] MODEL_RESPONSE role=SignalOrRequest decision=request
    response: {"message":"This is a request","decision":"request","artifact":""}
[audit run=20260625-101500-9f3a #5] TRANSITION role=SignalOrRequest decision 'request' -> HandleRequest
```

### As a web page (watch live)

The audit web page polls a trail and renders events as they arrive, so you can watch a prompt being
processed and scroll through the full trail.

- **From the prompt page:** clicking **Send** reveals a **"🔎 View audit trail for this prompt ↗"** link
  and opens the audit page in a new tab for that exact prompt. It updates live — including after you answer
  a clarifying question, since the answer continues the **same** run.
- **By run id (e.g. a terminal prompt):** find the `run=<id>` value in `audit.log` and open
  `http://localhost:8080/audit.html?run=<run-id>`.

Raw JSON is available at `GET /audit/{promptId}`, `GET /audit/run/{runId}`, and `GET /audit` (recent runs).

Because a request's run id persists across clarification rounds, **one trail covers the whole request**,
including every clarifying iteration (visible as repeated `ROLE_STARTED` for `HandleRequest` with
increasing iteration numbers).

Full details are in **[AuditInstructions.md](AuditInstructions.md)**.

---

## Tool registry

RoleFlow has an internal **tool registry** exposed over the **Model Context Protocol (MCP)**, so tools can
be discovered and used by internal clients. The first tool is **`web_search`** (DuckDuckGo, with a Lite
fallback) — the key tool for resolving requests for information.

- **Discover:** `POST /mcp` with `{"jsonrpc":"2.0","id":1,"method":"tools/list"}` (or `GET /mcp/tools`).
- **Use:** `POST /mcp` with `{"jsonrpc":"2.0","id":2,"method":"tools/call",
  "params":{"name":"web_search","arguments":{"query":"…"}}}`.
- **Add a tool:** add a `@Component` that implements `ToolProvider` — it is registered automatically.

```bash
curl -s http://localhost:8080/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call",
       "params":{"name":"web_search","arguments":{"query":"model context protocol","max_results":3}}}'
```

Full details (adding, registering, discovering, and using tools) are in
**[Tool_Registry.md](Tool_Registry.md)**.

---

## How to end the application

- Press **Ctrl+C** in the terminal where the application is running. Spring Boot shuts down and stops the
  managed `llama-server` process automatically.
- If you started it in the background, stop the Java process for `RoleFlowApplication` (for example, via
  Task Manager on Windows or `pkill -f RoleFlowApplication` on macOS/Linux).

(Typing `exit` or `quit` at the `RoleFlow>` prompt only stops reading terminal input — it does **not**
shut down the application. Use **Ctrl+C** to fully stop it.)

---

## Configuration

Settings live in [`src/main/resources/application.properties`](src/main/resources/application.properties)
and can be overridden on the command line, e.g. `--server.port=9000`. The most useful options:

| Property                  | Default                | Description                                              |
|---------------------------|------------------------|----------------------------------------------------------|
| `server.port`             | `8080`                 | Port for the web page and REST API.                      |
| `roleflow.system-prompt`  | *(empty)*              | Default system prompt (plain mode only, when no workflow loaded). |
| `roleflow.terminal.enabled` | `true`               | Read prompts from stdin. Set `false` to disable.         |
| `roleflow.config`         | `config/roleflow.active` | Workflow file. Empty/missing → plain single-call mode.  |
| `roleflow.goals-dir`      | `goals`                | Directory where goal and plan files are written.         |
| `roleflow.max-steps`      | `20`                   | Safety cap on role steps processed per prompt.           |
| `roleflow.audit.log-file` | `audit.log`            | Audit log file to follow with `tail -f`.                 |
| `roleflow.audit.max-trails` | `50`                 | Recent runs kept in memory for the audit web view.       |
| `prompt.max-tokens`       | `1024`                 | Default response length cap (also the response reserve). |
| `memory.max-tokens`       | `8192`                 | `MAX_TOKEN_SIZE` — memory compacts to stay under this.   |
| `memory.chars-per-token`  | `4`                    | Chars-per-token ratio used to estimate sizes locally.    |
| `memory.summary-max-tokens` | `1024`               | Max length of a generated summary during compaction.     |
| `llama.binary`            | *(empty → on PATH)*    | Full path to `llama-server` if not on `PATH`.            |
| `llama.profile`           | `small`                | `small` / `medium` / `large` model presets.             |
| `llama.model-path`        | *(empty)*              | Use a local `.gguf` file instead of a profile download.  |
| `llama.port`              | `8081`                 | Port `llama-server` listens on.                          |
| `llama.manage-server`     | `true`                 | Set `false` to use an externally started `llama-server`. |

---

## Running the tests

```bash
./mvnw test          # macOS / Linux / Git Bash
mvnw.cmd test        # Windows
```

The test suite never launches `llama-server` or reads from stdin (those are disabled in
`src/test/resources/application.properties`), so it runs fast and offline. The memory and compaction
tests use a fake summarizer and a deterministic token estimator, so they assert the budget invariant
without calling a model.

---

## Project layout

```
src/main/java/com/example/roleflow/
  RoleFlowApplication.java     Spring Boot entry point
  LlamaServerManager.java      Launches and supervises llama-server
  LlamaClient.java             Calls llama-server's /v1/chat/completions (ask + chat)
  AskController.java           POST /ask REST endpoint
  TerminalPromptRunner.java    Reads prompts from the terminal (stdin)
  ConversationService.java     Runs a prompt: drives the workflow or a single call
  ConversationMemory.java      Session memory + Claude Code-style compaction
  Summarizer.java              Interface for folding old turns into a summary
  LlmSummarizer.java           Default summarizer, backed by the model
  TokenEstimator.java          Local token-size estimation
  Message.java                 A chat message (role + content)
  RoleFlowConfig.java          Loads/parses config/roleflow.active into roles
  Role.java                    One workflow step (action + transitions)
  RoleFlowReply.java           Parses the model's {message,decision,artifact} JSON
  RoleFlowSession.java         Per-session flow state (current role, run id, artifacts)
  RoleFlowEngine.java          Drives a prompt through the roles (emits audit events)
  GoalFileWriter.java          Writes goal/plan files into goals/
  AuditEvent.java              One audit event (role, prompts, response, transition…)
  AuditService.java            Collects audit trails; writes the audit log
  AuditView.java               A trail snapshot returned to the audit web page
  AuditController.java         GET /audit endpoints for the audit page
src/main/resources/
  application.properties       Configuration
  logback-spring.xml           Routes the audit logger to audit.log
  static/index.html            The prompt web page (with the audit link)
  static/audit.html            The live audit web page
config/
  roleflow.active              The role workflow the engine runs
  roleflow.proposed            The human-authored source for the workflow
goals/                         Goal and plan files written for requests
src/test/java/com/example/roleflow/
  ...Test.java                 Unit and context tests
CURRENT_PROCESS.md             Detailed explanation of the role workflow
AuditInstructions.md           How to use the audit log and audit web page
```
