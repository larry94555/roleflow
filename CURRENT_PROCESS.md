# CURRENT_PROCESS.md

This document explains the workflow RoleFlow runs for every prompt, as defined by
[`config/roleflow.active`](config/roleflow.active). It is generated from (but not identical to)
[`config/roleflow.proposed`](config/roleflow.proposed); the wording was adjusted so the steps run
reliably through the engine.

## The idea

A user prompt is not answered by a single model call. Instead it is driven through a sequence of
**roles**. Each role is, for all purposes, a **system prompt** that surrounds the unchanging user prompt
so the model performs one specific job (classify, clarify, build a goal, build a plan, respond). After a
role runs, a **transition** decides which role runs next. Because every role call goes through the shared
[conversation memory](README.md#conversation-memory-and-compaction), each role sees the output of the
earlier ones, and clarifying questions/answers accumulate as context without disrupting the flow.

The same workflow is used whether the prompt arrives from the **terminal** or the **web page**.

## The protocol (how a role talks to the engine)

For each role the engine builds a system prompt from the role's `Action` and sends it with the user
prompt. The model must reply with a single JSON object:

```json
{"message": "<text to show the user>", "decision": "<one allowed decision>", "artifact": "<file content or empty>"}
```

- `message` — what the user sees (only the message from the role where the run ends or pauses is shown).
- `decision` — drives the transition (e.g. `signal`, `request`, `clear`, `unclear`, `cancelled`).
- `artifact` — for steps that produce a file (goal, plan), the full human-readable content.

Parsing is tolerant: the engine extracts the object if the model wraps it in prose, accepts raw line breaks
inside strings, accepts a duplicate key (keeping the first), and tolerates **invalid backslash escapes** such
as LaTeX (`\( n^2 \)`) — which would otherwise make the whole reply fail to parse and lose the `decision`. If
there is no JSON at all, the whole reply is treated as the `message`.

## Transition rules

After a role returns, the engine resolves the model's `decision` against the role's `Transition`:

| Outcome | Meaning |
|---------|---------|
| a **different** role name | continue immediately to that role with the **same** user prompt (no new user input) |
| the **same** role name (self-transition) | re-run that role **automatically**, no user input (e.g. a reviewer re-reviewing its own updated output) |
| **`await`** | return the message to the user and **wait** for their next prompt, then resume at the same role (clarifying questions) |
| **`done`**, or a target that does not exist | the run is **complete**; the next prompt starts a fresh run |

A safety cap (`roleflow.max-steps`, default 20) stops runaway loops (including a reviewer that keeps
recommending changes).

## The sixteen roles

```
                         ┌──────────────────────┐
   user prompt ─────────▶│ 1. TopicAnalyzer     │  identifies topics
                         └──────────┬───────────┘
                      topics │            │ none
                             ▼            │
                   ┌──────────────────────┐    │
                   │ 2. TopicContextBuilder│   │  web_search per topic
                   │   (computed)          │    │  (no topic missed)
                   └──────────┬───────────┘    │
                              └──────┬──────────┘
                                     ▼
                         ┌─────────────────────┐
                         │ 3. SignalOrRequest   │  (Classifier)
                         └──────────┬───────────┘
                       signal │            │ request
                              ▼            ▼
                   ┌──────────────────┐   ┌─────────────────────┐
                   │ 4. SignalResponse│   │ 5. HandleRequest     │◀──┐ unclear
                   │    (done)        │   │    (Clarifier)       │───┘ -> await
                   └──────────────────┘   └───┬─────────────┬────┘   (ask user, wait)
                                       clear  │             │ cancelled
                                              ▼             ▼
                                   ┌──────────────────┐    done
                                   │ 6. GoalBuilder    │  writes goal file
                                   └────────┬──────────┘
                                            ▼
                                   ┌──────────────────┐
                                   │ 7. PlanBuilder    │  writes plan file
                                   └────────┬──────────┘
                                            ▼
                                   ┌──────────────────┐ change
                                   │ 8. PlanReviewer   │◀──┐ (rewrite plan,
                                   │  may rewrite plan │───┘  re-review)
                                   └────────┬──────────┘
                                        ok  ▼
                                   ┌──────────────────┐   per step, CALL a function
                                   │ 9. StepReviewer   │──────────────────────────────┐
                                   │ classifies steps  │◀─────────────────────────────┘
                                   └────────┬──────────┘   function returns   ┌───────────────────────┐
                                            │              (10 SubgoalPlanner, │ each function adds a   │
                                            │               11 ActionPlanner,  │ detail section for the │
                                            │               12 InformationPlanner,│ step to the plan file │
                                            │               13 DecisionPlanner) └───────────────────────┘
                                            ▼
                                   ┌──────────────────────┐ update (TODO list)
                                   │14. PlanDetailReviewer │──────────────┐
                                   │  completeness check   │◀────────────┐│
                                   └────────┬──────────────┘ i < 3       ││
                                  sufficient │     ┌──────────────────────┘│ calls SubgoalPlanner
                                            │     ▼                        │ per TODO step
                                            │  ┌──────────────────┐ ───────┘ (adds a refinement section)
                                            │  │15. PlanDetailer  │
                                            │  │ Iteration: 3     │── i >= 3 ─┐
                                            │  └──────────────────┘           │
                                            ▼                                 ▼
                                   ┌──────────────────┐◀──────────────────────┘
                                   │16. ResponseBuilder│
                                   │     (done)        │
                                   └──────────────────┘
```

1. **TopicAnalyzer (Topic identifier)** — identifies the topics relevant to the prompt (areas of knowledge
   that affect how it should be interpreted, e.g. mathematics, programming), at most three, or none for a
   very general prompt. The topics (one per line in the artifact) are parsed into the session's topic list
   and recorded in the audit trail. → `topics` to role 2, `none` to role 3 (default forward to role 3).
2. **TopicContextBuilder (Information retrieval)** — for **every** identified topic, gathers background
   details by running the `web_search` tool **once per topic**, so no topic is missed. This role is
   **computed deterministically by the engine** (no model call) — see
   [Topic context](#topic-context). The combined context is cached on the session for later roles. → role 3.
   *(Reached only when topics were found; otherwise TopicAnalyzer goes straight to role 3.)*
3. **SignalOrRequest (Classifier)** — decides whether the prompt is a `signal` (a pleasantry or piece of
   information, e.g. "Hello", "Thanks") or a `request` (asks for an action, a state change, or
   information). → `signal` to role 4, `request` to role 5.
4. **SignalResponse (Responder)** — acknowledges information or replies naturally. → `done`. **No files.**
5. **HandleRequest (Clarifier)** — makes the success criteria unambiguous. If clear, restates them
   (`clear` → role 6). If not, asks one clarifying question (`unclear` → `await`, pausing for the user). If
   the user cancelled, acknowledges it (`cancelled` → `done`). Capped at **one** clarifying question via
   `Iteration: 2` (see [Iteration](#iteration)), so it can't ask the same thing repeatedly.
6. **GoalBuilder (Goal author)** — constructs the goal (the criteria for when the request is satisfied,
   one-time or ongoing) and **writes a goal file** to `goals/`. → role 7.
7. **PlanBuilder (Plan author)** — using the goal, builds a four-phase plan (Preparation, Action,
   Verification, Next steps) and **writes a plan file** to `goals/`. The plan's structure is enforced
   deterministically (see [Plan structure enforcement](#plan-structure-enforcement)). It declares
   `Skills: mathematics`, so on a mathematics prompt it is given the mathematics skill's guidance (see
   [Skills](#skills)). → role 8.
8. **PlanReviewer (Plan reviewer)** — reviews the plan's high-level steps. It is given the **topic context**
   gathered by TopicContextBuilder and uses it to flag steps that contradict how the topic actually
   works — for example, treating the discovery of a mathematical counterexample as a defect to fix. If a
   change is warranted, it rewrites the **updated plan file** and re-reviews (`change` → itself,
   automatically); otherwise it confirms no change is needed (`ok` → role 9). Its verdict is recorded in
   the audit trail.
9. **StepReviewer (Step reviewer)** — classifies every step of the plan as `decision-point`,
   `request-for-information`, `action`, or `subgoal`, and records the classifications in the audit trail.
   This role is **computed deterministically by the engine** (no model call) — see
   [Step classification](#step-classification). Then, for **each** step, it **calls the function** mapped to
   that step's classification (roles 10–13), which adds a detail section for the step to the plan file (see
   [Functions](#functions)). → role 14.
10. **SubgoalPlanner (Function)** — called for a `subgoal` step; produces a short four-phase breakdown of
    substeps for that step. **Returns** to the caller.
11. **ActionPlanner (Function)** — called for an `action` step; classifies it as "write code", "write and
    run code", "invoke a tool", or "invoke a service". **Returns** to StepReviewer.
12. **InformationPlanner (Function)** — called for a `request-for-information` step; classifies how the
    information is obtained ("request from web", "research on web", "process through code", "process through
    a tool", "web and code"). **Returns** to StepReviewer.
13. **DecisionPlanner (Function)** — called for a `decision-point` step; states the decision made or how it
    will be made ("will ask user", "request information", "do tests"). **Returns** to StepReviewer.
14. **PlanDetailReviewer (Plan detail reviewer)** — reviews the goal, plan, and step details for
    **completeness**. If every step is worked out, `sufficient` → role 16. Otherwise `update` → role 15,
    emitting a **TODO list** of the steps that are still `ambiguous` (need decision points/assumptions) or
    `too-high-level` (need a breakdown).
15. **PlanDetailer (Plan detailer)** — for each TODO step, **calls SubgoalPlanner** with the extra
    instruction implied by the step's issue, adding a refinement section to the plan file. **Computed** (no
    model call of its own). Capped by `Iteration: 3`: while under the cap it loops back to role 14 for
    another review; once the cap is reached it goes to role 16 (see [Iteration](#iteration)).
16. **ResponseBuilder (Final responder)** — gives a brief confirmation that the goal and plan were created.
    The engine then appends the exact file location as a `file:///` URL (see [Files written](#files-written)),
    and the topics considered are added to the reply. → `done`.

Roles 10–13 are **functions**: unlike the other roles they are not transition points — they are *called*
(by StepReviewer or PlanDetailer) and *return* (see [Functions](#functions)). Roles 14–15 form a bounded
**detail-refinement loop** after the per-step details are added.

## Topic context

The first thing a run does is work out what the prompt is *about*. **TopicAnalyzer** lists the relevant
topics (one per line in its artifact); the engine parses them with
[`TopicList`](src/main/java/com/example/roleflow/TopicList.java) (de-duplicated, bullet/label prefixes
stripped, capped) into the session's topic list.

Gathering the background for those topics is **computed deterministically** rather than left to the model,
so that **no identified topic is ever missed**. **TopicContextBuilder** (`Compute: build-topic-context`)
loops over **every** topic in the session list and calls
[`TopicResearcher`](src/main/java/com/example/roleflow/TopicResearcher.java) — which runs the `web_search`
tool once per topic — then combines the results into a single topic-context block cached on the session.
Each search is recorded as a `VALIDATION` event (`gathered web context for topic '…'`), so the audit trail
shows exactly which topics were researched.

Later, **PlanReviewer** declares `Research: topic` in the config; the engine injects the **already-gathered**
context block into its system prompt (it performs no search of its own). This lets the reviewer sanity-check
the plan against how the topic actually works. The topics are also appended to the final user-facing reply
as `Topics considered: …`. When there are no topics, TopicContextBuilder is skipped entirely and no topic
footer is added.

## Skills

A **skill** is a block of reusable domain guidance that teaches a role *how to think* about a subject (where
a tool lets a role *do* something). Skills are contributed by `SkillProvider` beans and collected by the
[`SkillRegistry`](src/main/java/com/example/roleflow/SkillRegistry.java), mirroring the tool registry.

A role lists the skills it may apply in its `Skills:` field. The engine injects a skill into the role's
system prompt only when **both** the role declares it **and** the skill's name matches one of the run's
identified topics — so a role gets domain knowledge exactly when the subject calls for it. The use of a
skill is recorded as a `VALIDATION` event (`applied skill(s): …`) on the role.

The first skill is **`mathematics`**, applied by **PlanBuilder** (`Skills: mathematics`). It corrects two
recurring mistakes the model makes on math prompts: treating "searched the range and found no counterexample"
as a failure to retry or extend, AND treating a counterexample that *is* found as something to "handle" or
fix. The skill explains that a search over a fixed range is a **complete** result whichever outcome occurs —
both a counterexample and "none found" need only to be **reported**, there is nothing to handle, fix, retry,
or resolve — and that the goal is to gain information about a conjecture, not to prove it. See
[Skill_Registry.md](Skill_Registry.md) for how to create and wire a new skill.

### Changeable vs information-only state

Independent of any skill, **PlanBuilder** and **PlanReviewer** apply a general check to Phase 3
(Verification) and Phase 4 (Next steps): distinguish a **changeable** state (something the plan can act on or
modify — a file to create, a backup to schedule) from an **information-only** state (a fact that can only be
observed and reported — e.g. whether a counterexample exists). Verification and Next steps may confirm,
report, or act on changeable state, but must **never** try to change an information-only result. PlanReviewer
flags any step that tries to "handle", fix, retry, or re-run an information-only result as out of place. This
is what stops a plan from adding a step like *"if a counterexample is found, the application must handle it"*.

## Plan structure enforcement

The plan format is fixed, so the engine enforces it in code rather than trusting the model. After a
plan-producing role (PlanBuilder, or PlanReviewer when it rewrites the plan) returns its plan,
[`PlanValidator`](src/main/java/com/example/roleflow/PlanValidator.java) checks that it:

- contains, in order, the four headers **Phase 1 - Preparation**, **Phase 2 - Action**,
  **Phase 3 - Verification**, **Phase 4 - Next steps**, each followed by at least one step; and
- **Phase 1 calls out the assumptions and decision points** implied by the request — for example, the
  programming language when an application is requested. These must be made directly (`Assumption: …`,
  `Decision: …`) or deferred as preparation steps to figure them out, so a decision point is never
  silently ignored.

If the check fails, the engine first tries to **normalize** the plan into the canonical shape with
[`PlanNormalizer`](src/main/java/com/example/roleflow/PlanNormalizer.java) before giving up on it. Smaller
models reliably organise a plan under "Phase 1..4" but vary the styling — e.g. `# Phase 1` instead of
`## Phase 1 - Preparation`, and `## Assumption: …` markdown sub-headings instead of `- ` bullet steps. The
normalizer rewrites whatever the model emitted into the fixed headers with `- ` steps (dropping any preamble
before Phase 1). If the normalized plan validates, it is used and a `VALIDATION` event records
`plan normalized into the canonical four-phase structure`. An already-canonical plan validates on the first
pass and is left untouched.

Only if normalization still does not yield a valid plan does the engine re-prompt the model with the concrete
problems as feedback (up to three attempts) before proceeding. Each check is recorded in the audit trail as a
`VALIDATION` event, so a rejected/retried plan is visible.

> **Duplicate JSON keys.** Smaller models sometimes emit a field twice — the real value first, then an empty
> template echo (e.g. `"artifact":"…plan…","artifact":""`). The reply parser
> ([`RoleFlowReply`](src/main/java/com/example/roleflow/RoleFlowReply.java)) keeps the **first** occurrence of
> each key, so a trailing empty duplicate can never clobber the model's actual content.

## Step classification

The step categories are fixed, so StepReviewer is **computed deterministically by the engine** rather than
by the model (a role declares this with `Compute: classify-steps` in the config). The engine extracts the
plan's steps and classifies each with [`StepClassifier`](src/main/java/com/example/roleflow/StepClassifier.java) —
a transparent keyword heuristic — into `decision-point` (an assumption or a choice that must be made),
`request-for-information`, `action`, or `subgoal`. No model call is made for this role; the per-step
classifications are recorded as the role's `ROLE_RESULT` (e.g.
`Step 1: Decision: use Python -> decision-point`) and a `VALIDATION` event notes how many steps were
classified.

> **Reviewer results in the audit.** Every role's human-readable result is recorded as a `ROLE_RESULT`
> event, so the PlanReviewer's verdict ("no changes needed" / its justification) and the StepReviewer's
> per-step classifications are visible in both the audit log and the audit web page.

## Functions

Most roles are **transition points**: a role runs and the engine moves on to the next role. A **function**
is the one role type that is different — it is *called* by another role, does its work, and *returns* to the
caller. A function is marked with `Kind: function` and its transition is `return`; it is never reached by a
`Transition`, only through a `Calls` mapping.

The first use of functions is to **add detail to the plan**. After **StepReviewer** classifies the steps, it
calls — for each step — the function mapped to that step's category (its `Calls` field):

| Step category | Function called | What it adds |
|---------------|-----------------|--------------|
| `subgoal` | `SubgoalPlanner` | a four-phase breakdown of substeps for the step |
| `action` | `ActionPlanner` | "write code" / "write and run code" / "invoke a tool" / "invoke a service" |
| `request-for-information` | `InformationPlanner` | "request from web" / "research on web" / "process through code" / "process through a tool" / "web and code" |
| `decision-point` | `DecisionPlanner` | the decision made, or "will ask user" / "request information" / "do tests" |

Each function gets a **focused prompt**: only the single item it is detailing (plus, for PlanDetailer, the
issue-specific instruction). It is deliberately NOT shown the whole goal/plan, the accumulated step details,
or the file locations — that surrounding text (which includes earlier functions' code/output) would just be
copied by a small model instead of classified. So functions declare no `Reads:`.

Each function call is a real model turn through [`RoleFlowEngine`](src/main/java/com/example/roleflow/RoleFlowEngine.java)
and is recorded in the audit trail **exactly like a role** — `ROLE_STARTED`, `MODEL_REQUEST`,
`MODEL_RESPONSE`, `ROLE_RESULT`, an `ARTIFACT_WRITTEN` for the updated plan file, and a `TRANSITION` noting
the `return` to the caller. The function's output is appended to the plan file as one subsection under a new
`# Step Details` heading; the **high-level plan is left unchanged** — functions only *add* detail (see
[Generated_Plans.md](Generated_Plans.md)).

A function that picks from a **fixed set of categories** (ActionPlanner, InformationPlanner, DecisionPlanner)
lists them in a `Classifies:` field. The engine detects which category the reply states and records it as a
`VALIDATION` event (`classified the step as 'write code'`), so the classification is visible in the audit
trail; if the reply states none of them, that is recorded too (`did not state a recognized category`).

**PlanDetailer** also calls a function: for each step on PlanDetailReviewer's TODO list it calls
`SubgoalPlanner`, passing an **extra instruction** ("add the decision points and assumptions it needs" for an
ambiguous step, or "break it down into concrete substeps" for a too-high-level one). Those refinements are
appended as `## Refinement: <step> — <issue>` subsections. This is the "additional prompt information a role
can provide to a function when it makes a call."

## Iteration

A role may declare `Iteration: <n>` to cap how many times it runs in a single request. The engine counts each
entry to the role; once the count reaches the cap it substitutes a synthetic **`limit`** decision for the
role's normal one, so the role's `limit -> …` transition is taken instead of looping. The count is recorded
in the audit trail (`iteration 2 of at most 3`, then `iteration 3 reached the limit of 3`).

The detail-refinement loop uses this: **PlanDetailReviewer** (`update` → PlanDetailer, `sufficient` →
ResponseBuilder) and **PlanDetailer** (`Iteration: 3`, `limit -> ResponseBuilder`, default → PlanDetailReviewer)
keep refining the plan until it is sufficiently detailed or three passes have run — whichever comes first.
(The overall `roleflow.max-steps` safety cap still applies as a backstop.)

**HandleRequest** also uses it (`Iteration: 2`, `limit -> GoalBuilder`): it may ask **one** clarifying
question (round 1 → `await`), and on the answer (round 2) the cap forces it to proceed, so a model that keeps
returning `unclear` can never ask the same question forever.

## What the user experiences

- **A signal** ("Hello", "Thanks for that"): analyse topics → classify → respond. One visible answer,
  **no files created**.
- **A clear request**: analyse topics (and gather their context) → classify → clarify (clear) → goal →
  plan → review → classify steps → detail each step (a function call per step) → review the plan for
  completeness, refining it up to 3 times → respond, in one turn. One
  visible answer (from ResponseBuilder), and **a single combined `plan_*.md` file** in `goals/` (goal
  section, then plan, then a per-step detail section — see [Generated_Plans.md](Generated_Plans.md)). The
  topics considered are listed at the end of the reply.
- **A request needing clarification**: …→ clarify asks a question and **stops**. The user answers
  in their next prompt, which resumes at the Clarifier and continues to completion. The combined file keeps
  the request's run id, so it is recognizable as belonging to the request.
- **A cancelled request**: …→ clarify acknowledges the cancellation → done. **No files.**

> **Note on calls.** Only the model-driven roles make a `llama-server` call; the two computed roles
> (TopicContextBuilder, StepReviewer) run in code. TopicContextBuilder still issues one `web_search` per
> identified topic, and StepReviewer makes **one function call (a model turn) per plan step** to add its
> detail section.

## Files written

For a request, a **single** file is written to the `goals/` directory (configurable via
`roleflow.goals-dir`):

- `plan_<prefix>_<timestamp>.md` — the combined document: a `# Goal` section (from GoalBuilder), a `# Plan`
  section (from PlanBuilder, reviewed), and a `# Step Details` section (one `## Step N: …` subsection per
  step, added by the planner **functions** StepReviewer calls). The goal is no longer a separate file, so the
  directory holds one file per request instead of two. See [Generated_Plans.md](Generated_Plans.md) for the
  full format and rendering details.

The `<prefix>` is a short, human-readable label summarizing the session's first prompt (e.g.
`search-integers-counterexample`), kept unique across sessions by appending a number when needed
(`legendre`, `legendre-2`, …). It is the leading part of the run id, so a whole session's file and log
lines can be found with a single `grep <prefix>_`. The `<timestamp>` (`yyyyMMdd-HHmmss`) is created when a
request's run begins and is reused across clarification pauses.

Internally the goal and plan are kept as **separate** pieces in the session — the goal is captured but not
written to its own file, and the plan role composes the two into the on-disk document
([`PlanDocument`](src/main/java/com/example/roleflow/PlanDocument.java)). A role is shown the prior
artifacts' **content** when it produces output (e.g. PlanBuilder builds on the goal) or when it declares
`Reads:` in the config. A **pure reporting** role (ResponseBuilder) is given only the file **location** and
told not to paste the contents. When the run completes, the engine appends the authoritative link to the
reply as a `file:///` URL, for example:

```
The goal and plan were created successfully.

Files created:
- Plan file: file:///C:/Users/larry/github/roleflow/goals/plan_legendre_20260625-164426.md
```

This way the exact path comes from the engine rather than being transcribed (and possibly garbled) by the
model. From the **web page** the link opens a GitHub-style rendered view of the document (the
[`GoalFileController`](src/main/java/com/example/roleflow/GoalFileController.java) renders the Markdown to
HTML; append `?raw=1` for the unrendered source).

## Relationship to memory

A whole turn — the user prompt through all of its roles and per-step function calls — is recorded into the
conversation memory as a **single exchange** (prompt → the final answer the user sees). The workflow's many
internal role/function calls run *statelessly*: they read the current memory (so earlier user turns and
clarifying answers are still context) but are **not** each written back to it.

This is deliberate. Within one request the engine already gives each role the goal, plan, and step details it
needs through its system prompt, so the roles do not need each other's raw replies in memory. Recording every
role's reply would instead fill memory with near-duplicate turns (the user prompt is identical across roles),
which makes a small model **echo its own previous output** rather than perform each role — the source of a
class of bugs where every role returned the same JSON. Keeping the internal calls out of memory fixes that.
If a session runs long enough to approach the token budget, the (per-turn) memory still compacts older turns
into a summary exactly as described in the README.

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `roleflow.config` | `config/roleflow.active` | Path to the workflow file. Empty/missing → plain single-call mode. |
| `roleflow.goals-dir` | `goals` | Where the combined goal-and-plan files are written. |
| `roleflow.max-steps` | `20` | Safety cap on role steps per prompt. |
