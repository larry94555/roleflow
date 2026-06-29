# AuditInstructions.md

RoleFlow records an **audit trail** for every prompt it processes. The trail shows, for each role that is
triggered: the **prompt** that came through, the **system prompt** used, the **model's response**, the
**decision**, how the **transition** was handled, and any **clarification iterations**. The trail is
available two ways: as a **log file** you can follow with `tail -f`, and as a **web page** you can watch
live while a prompt is processed.

This applies to prompts from both the **terminal** and the **web page** — they share the same workflow and
the same audit.

---

## What the audit captures

For each prompt, the trail is an ordered list of events. The event types are:

| Event | Meaning |
|-------|---------|
| `RUN_STARTED` | Processing of a prompt began (records the source: `web`, `terminal`, …). |
| `ROLE_STARTED` | A role started, with its **iteration** number within the run (2+ means a clarifying repeat). |
| `MODEL_REQUEST` | The **system prompt** and **user prompt** sent to the model for that role. |
| `MODEL_RESPONSE` | The **raw response** from the model and the **decision** it chose. |
| `ARTIFACT_WRITTEN` | A goal or plan file was written (with its path). |
| `TRANSITION` | How the decision was handled — e.g. `decision 'request' -> HandleRequest`, `-> done`, or a self-transition to ask the user. |
| `CLARIFICATION_PAUSE` | A role asked the user a question; the run pauses until the user replies. |
| `RUN_COMPLETED` | The run finished. |
| `MODEL_ERROR` | A model call failed (with the error message). |

Because a request's run id persists across clarification rounds, **one trail covers the whole request**,
including every clarifying back-and-forth. The repeated `ROLE_STARTED` for `HandleRequest` (iteration 2, 3,
…) is how you see the clarifying iterations.

---

## Following the audit in the logs (`tail -f`)

Every event is written to the audit log file — `audit.log` in the working directory by default. While the
application is running, follow it in another terminal:

```bash
tail -f audit.log
```

Every line carries the run id `run=<prefix>_<timestamp>`, whose leading **`<prefix>`** is a short,
human-readable label summarizing the session's first prompt (e.g. `backup-notes`). The same prefix is used
in the goal/plan file names, so you can isolate one whole session — across logs and files — with a single
search:

```bash
grep "backup-notes_" audit.log        # everything for that session
tail -f audit.log | grep "backup-notes_"
```

Prefixes are kept unique across sessions; if the same summary would recur, a number is appended
(`backup-notes`, `backup-notes-2`, …). A request looks like this (abridged):

```
2026-06-25 10:15:00.101 [audit run=backup-notes_20260625-101500 #1] RUN_STARTED session=backup-notes source=web
2026-06-25 10:15:00.102 [audit run=backup-notes_20260625-101500 #2] ROLE_STARTED role=SignalOrRequest iteration=1
2026-06-25 10:15:00.103 [audit run=backup-notes_20260625-101500 #3] MODEL_REQUEST role=SignalOrRequest
    system-prompt: You are RoleFlow ... Current role: SignalOrRequest ...
    user-prompt: Set up a weekly backup of my notes folder
2026-06-25 10:15:01.880 [audit run=backup-notes_20260625-101500 #4] MODEL_RESPONSE role=SignalOrRequest decision=request
    response: {"message":"This is a request","decision":"request","artifact":""}
2026-06-25 10:15:01.881 [audit run=backup-notes_20260625-101500 #5] TRANSITION role=SignalOrRequest decision 'request' -> HandleRequest
2026-06-25 10:15:01.882 [audit run=backup-notes_20260625-101500 #6] ROLE_STARTED role=HandleRequest iteration=1
...
2026-06-25 10:15:05.210 [audit run=backup-notes_...] ARTIFACT_WRITTEN role=GoalBuilder kind=goal path=file:///.../goals/goal_backup-notes_20260625-101500.md
...
2026-06-25 10:15:07.640 [audit run=backup-notes_...] TRANSITION role=ResponseBuilder decision 'done' -> done (run complete)
2026-06-25 10:15:07.641 [audit run=backup-notes_...] RUN_COMPLETED finalRole=ResponseBuilder
```

The audit is also echoed to the console where the app runs. The `run=<id>` value is the run id; you can
open that exact run in the web view (see below).

---

## Watching the audit as a web page

The audit web page (`audit.html`) **polls** a trail and renders events as they arrive, so you can watch a
prompt being processed step by step and scroll through the full trail.

### From the prompt web page (recommended)

1. Open `http://localhost:8080/` and enter a prompt.
2. When you click **Send**, RoleFlow:
   - shows a **"🔎 View audit trail for this prompt ↗"** link, and
   - **opens the audit page in a new tab** for that exact prompt.
3. The audit tab updates live as each role runs. For a request that needs clarification, the audit keeps
   updating after you answer the clarifying question, because the answer continues the **same** run.

The link is available immediately (the page creates the audit id before sending), so you can watch from
the very first role.

### From the terminal

Terminal prompts are audited the same way. After each prompt the terminal prints a **clickable** audit-trail
link (and opens the audit web page in your browser once per session, like the prompt web page does), so you
can watch the run live without leaving the command line. You can also open it by run id — find the
`run=<id>` value in `audit.log`, then open:

```
http://localhost:8080/audit.html?run=<run-id>
```

(Clickable links and auto-open can be turned off with `roleflow.terminal.hyperlinks=false` and
`roleflow.terminal.open-audit=false`.)

### Endpoints (if you want the raw JSON)

| Endpoint | Returns |
|----------|---------|
| `GET /audit/{promptId}` | The trail for a web prompt id (pending until the run is linked). |
| `GET /audit/run/{runId}` | The trail for a run id (404 if unknown). |
| `GET /audit` | Recent runs, newest first. |

Each trail is `{ promptId, runId, source, completed, events: [ … ] }`. `completed` is `false` while the
run is still in progress (including while paused for clarification), which is how the web page knows to
keep polling.

---

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `roleflow.audit.log-file` | `audit.log` | Path to the audit log file followed with `tail -f`. |
| `roleflow.audit.max-trails` | `50` | How many recent runs are kept in memory for the web view. |

The log file destination is wired in [`logback-spring.xml`](src/main/resources/logback-spring.xml) via the
`roleflow.audit.log-file` property. Older trails beyond `max-trails` are evicted from the in-memory store
(the log file keeps everything).

---

## Notes

- The audit stores trails **in memory** (bounded by `max-trails`) for the web view; the durable record is
  the log file. Restarting the application starts a fresh in-memory store.
- A single prompt produces **several** events because the workflow makes one model call per role — see
  [CURRENT_PROCESS.md](CURRENT_PROCESS.md) for the role flow.
