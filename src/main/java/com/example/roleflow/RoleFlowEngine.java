package com.example.roleflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Drives a single user prompt through the roles in {@code roleflow.active}.
 *
 * <p>For each role the engine builds a system prompt from the role's action, sends it together with the
 * (unchanging) user prompt to the model via a {@link ModelInvoker}, parses the structured reply, writes
 * any artifact to {@code goals/}, then follows the role's transition:
 * <ul>
 *   <li>a different role &rarr; continue immediately with the same user prompt;</li>
 *   <li>the same role &rarr; a clarifying question was asked, so return it and wait for the user;</li>
 *   <li>{@code done} or an unknown target &rarr; the run is complete.</li>
 * </ul>
 * Because every model call goes through the shared conversation memory, each role sees the output of the
 * earlier ones, and clarifying questions/answers accumulate as context without disrupting the flow.
 */
@Service
public class RoleFlowEngine {

    /** Makes one model call through the conversation memory. */
    @FunctionalInterface
    public interface ModelInvoker {
        String invoke(String systemPrompt, String userPrompt, Integer maxTokens, Double temperature)
                throws Exception;
    }

    /** How many times a plan-producing role is re-prompted when its plan fails the structure check. */
    private static final int PLAN_VALIDATION_ATTEMPTS = 3;

    private final RoleFlowConfig config;
    private final RoleFlowSession session;
    private final GoalFileWriter writer;
    private final SessionLabeler labeler;
    private final AuditService audit;
    private final TopicResearcher researcher;
    private final SkillRegistry skills;
    private final ObjectMapper mapper;
    private final int maxSteps;

    public RoleFlowEngine(RoleFlowConfig config, RoleFlowSession session, GoalFileWriter writer,
                          SessionLabeler labeler, AuditService audit, TopicResearcher researcher,
                          SkillRegistry skills, ObjectMapper mapper,
                          @Value("${roleflow.max-steps:20}") int maxSteps) {
        this.config = config;
        this.session = session;
        this.writer = writer;
        this.labeler = labeler;
        this.audit = audit;
        this.researcher = researcher;
        this.skills = skills;
        this.mapper = mapper;
        this.maxSteps = Math.max(1, maxSteps);
    }

    /**
     * Runs the workflow for one user prompt and returns the text to show the user (the message from the
     * role where the run completed or paused for clarification). Every step is recorded to the audit trail
     * for {@code runId}; {@code auditId} (an optional client-supplied id) is linked to that run so the
     * audit web page can find it.
     */
    public String run(String userPrompt, String systemOverride, Integer maxTokens, Double temperature,
                      ModelInvoker model, String auditId, String source) throws Exception {
        boolean fresh = session.isIdle();
        if (fresh) {
            // The run id carries a human-readable prefix summarizing this session's first prompt, and the
            // first prompt is kept as the topic for any role that researches the web.
            session.begin(config.firstRole().name(), labeler.newRunId(userPrompt), userPrompt);
        }
        String runId = session.runId();
        if (fresh) {
            audit.runStarted(runId, source);
        }
        audit.link(auditId, runId);

        String lastMessage = "";
        for (int step = 0; step < maxSteps; step++) {
            Role role = config.byName(session.currentRole());
            if (role == null) { // a transition pointed at a role that does not exist
                audit.runCompleted(runId, session.currentRole());
                return complete(lastMessage);
            }

            audit.roleStarted(runId, role.name());
            // Record which of the role's declared skills apply to this run, so the use of a skill is visible
            // in the audit trail alongside the topics that triggered it.
            List<Skill> applied = applicableSkills(role);
            if (!applied.isEmpty()) {
                List<String> names = applied.stream().map(Skill::name).toList();
                audit.validation(runId, role.name(), "applied skill(s): " + String.join(", ", names));
            }
            // A computed role (e.g. step classification) is produced deterministically in code; otherwise
            // call the model — and for a plan-producing role enforce the fixed four-phase structure.
            RoleOutcome outcome = role.isComputed()
                    ? computeRole(role, runId)
                    : invokeRole(role, buildSystemPrompt(role, systemOverride) + topicContextSection(role),
                            userPrompt, maxTokens, temperature, model, runId);
            String raw = outcome.raw();
            RoleFlowReply reply = outcome.reply();
            lastMessage = reply.message().isBlank() ? raw.trim() : reply.message();
            // Record the role's human-readable result so reviewer findings are visible in the audit trail.
            audit.roleResult(runId, role.name(), lastMessage, reply.decision());

            // A role that provides topics (TopicAnalyzer) feeds its reply into the session's topic list so
            // the TopicContextBuilder can gather context for every one of them. The model is told to put the
            // topics in "artifact" (one per line); a "none" decision means there are none, so the human
            // "message" is never mistaken for a topic.
            if (role.providesTopics()) {
                List<String> topics = "none".equalsIgnoreCase(reply.decision())
                        ? List.of()
                        : TopicList.parse(reply.artifact().isBlank() ? reply.message() : reply.artifact());
                session.setTopics(topics);
                audit.validation(runId, role.name(), topics.isEmpty()
                        ? "no relevant topics identified"
                        : "identified topics: " + String.join(", ", topics));
            }

            // A role that provides a TODO list (PlanDetailReviewer) feeds the steps it flagged as needing more
            // detail into the session, for PlanDetailer to refine. A "sufficient" decision means none.
            if (role.providesTodos()) {
                List<TodoItem> todos = "sufficient".equalsIgnoreCase(reply.decision())
                        ? List.of()
                        : TodoList.parse(reply.artifact().isBlank() ? reply.message() : reply.artifact());
                session.setTodos(todos);
                audit.validation(runId, role.name(), todos.isEmpty()
                        ? "plan is sufficiently detailed"
                        : "plan needs more detail for " + todos.size() + " step(s)");
            }

            if (role.hasOutput()) {
                // Only act when there is content AND it actually differs from what we already have, so a
                // reviewer that merely echoes the existing plan does not produce a spurious rewrite.
                String kind = role.outputKind();
                String artifact = outcome.artifact();
                String existing = session.artifactContents().get(kind);
                if (!artifact.isBlank() && !artifact.equals(existing)) {
                    if ("goal".equalsIgnoreCase(kind)) {
                        // The goal is NOT written to its own file; it is kept in the session and becomes the
                        // first section of the single combined plan document written by the plan role.
                        session.setArtifactContent(kind, artifact);
                    } else {
                        // The plan is written as ONE document: the goal section first, then the plan. The
                        // pure plan text is what later roles read (and what change-detection compares); the
                        // composed goal+plan document is what lands on disk.
                        session.addArtifact(kind, artifact,
                                writer.write(kind, runId,
                                        PlanDocument.compose(session.artifactContents().get("goal"), artifact)));
                        audit.artifactWritten(runId, role.name(), kind, session.artifactPaths().get(kind));
                    }
                }
            }

            // A role with function calls dispatches a function for each item and returns here before the role
            // transitions. StepReviewer calls one per classified plan step; PlanDetailer (Compute:
            // detail-todos) calls SubgoalPlanner for each step PlanDetailReviewer flagged as needing detail.
            if (role.hasCalls()) {
                if ("detail-todos".equalsIgnoreCase(role.compute())) {
                    runTodoDetailing(role, runId, maxTokens, temperature, model);
                } else {
                    runStepFunctions(role, runId, maxTokens, temperature, model);
                }
            }

            // The decision that drives the transition is normally the model's reply. A role with an iteration
            // limit (PlanDetailer) instead transitions on a synthetic "limit" decision once it has run the
            // capped number of times, so a refinement loop cannot run forever.
            String decision = reply.decision();
            if (role.hasIterationLimit()) {
                int count = session.incrementIteration(role.name());
                if (count >= role.iterationLimit()) {
                    decision = Role.LIMIT;
                    audit.validation(runId, role.name(),
                            "iteration " + count + " reached the limit of " + role.iterationLimit());
                } else {
                    audit.validation(runId, role.name(),
                            "iteration " + count + " of at most " + role.iterationLimit());
                }
            }

            String target = role.resolve(decision);
            if (target == null || Role.DONE.equalsIgnoreCase(target)) {
                // target == null means the decision matched no transition and the role has no default;
                // call that out explicitly so a misbehaving model is easy to spot in the audit trail.
                String detail = target == null
                        ? "decision '" + decision + "' matched no transition; run complete"
                        : "decision '" + decision + "' -> done (run complete)";
                audit.transition(runId, role.name(), detail);
                audit.runCompleted(runId, role.name());
                return complete(lastMessage);
            }
            if (Role.AWAIT.equalsIgnoreCase(target)) {
                // The role asked the user something; pause and resume at this same role on the next prompt.
                audit.transition(runId, role.name(),
                        "decision '" + decision + "' -> await (waiting for the user)");
                audit.clarificationPause(runId, role.name(), lastMessage);
                return lastMessage;
            }
            if (config.byName(target) == null) {
                audit.transition(runId, role.name(),
                        "decision '" + decision + "' -> " + target + " (no such role: run complete)");
                audit.runCompleted(runId, role.name());
                return complete(lastMessage);
            }
            // A transition to a different role advances; a transition to the same role re-runs it
            // automatically (e.g. a reviewer re-reviewing its own updated output).
            audit.transition(runId, role.name(),
                    "decision '" + decision + "' -> " + config.byName(target).name());
            session.moveTo(config.byName(target).name());
        }

        // Safety cap: stop runaway loops.
        audit.runCompleted(runId, session.currentRole());
        return complete(lastMessage);
    }

    /** The model output for one role, after any plan-structure retries. */
    private record RoleOutcome(String raw, RoleFlowReply reply, String artifact) {}

    /**
     * Invokes the model for one role and returns its output. For a plan-producing role the result is run
     * through {@link PlanValidator}: if the plan is structurally invalid, the model is re-invoked with the
     * concrete problems as feedback, up to {@link #PLAN_VALIDATION_ATTEMPTS} times. Every attempt and check
     * is recorded in the audit trail.
     */
    private RoleOutcome invokeRole(Role role, String systemPrompt, String userPrompt, Integer maxTokens,
                                   Double temperature, ModelInvoker model, String runId) throws Exception {
        String prompt = systemPrompt;
        String raw = "";
        RoleFlowReply reply = new RoleFlowReply("", "", "");
        String artifact = "";
        for (int attempt = 1; attempt <= PLAN_VALIDATION_ATTEMPTS; attempt++) {
            audit.modelRequest(runId, role.name(), prompt, userPrompt);
            try {
                raw = model.invoke(prompt, userPrompt, maxTokens, temperature);
            } catch (Exception e) {
                audit.modelError(runId, role.name(), e.getMessage());
                throw e;
            }
            reply = RoleFlowReply.parse(raw, mapper);
            audit.modelResponse(runId, role.name(), raw, reply.decision());
            artifact = effectiveArtifact(role, reply);

            // Only plan artifacts have a fixed structure to enforce.
            boolean validatePlan = "plan".equalsIgnoreCase(role.outputKind()) && !artifact.isBlank();
            List<String> problems = validatePlan ? PlanValidator.problems(artifact) : List.of();
            // Before re-prompting, try to coerce a structurally-wrong plan into the canonical four phases
            // (the model often uses "# Phase 1"/"## Assumption:" instead of "## Phase 1 - Preparation"/"- ").
            if (validatePlan && !problems.isEmpty()) {
                String normalized = PlanNormalizer.normalize(artifact);
                if (PlanValidator.isValid(normalized)) {
                    artifact = normalized;
                    problems = List.of();
                    audit.validation(runId, role.name(),
                            "plan normalized into the canonical four-phase structure");
                }
            }
            if (problems.isEmpty()) {
                if (validatePlan) audit.validation(runId, role.name(), "plan structure is valid");
                return new RoleOutcome(raw, reply, artifact);
            }
            if (attempt < PLAN_VALIDATION_ATTEMPTS) {
                audit.validation(runId, role.name(), "plan rejected (attempt " + attempt + "): "
                        + String.join("; ", problems) + " — retrying with feedback");
                prompt = systemPrompt + planFeedback(problems);
            } else {
                audit.validation(runId, role.name(), "plan still invalid after " + attempt
                        + " attempts: " + String.join("; ", problems) + " — proceeding");
            }
        }
        return new RoleOutcome(raw, reply, artifact);
    }

    /**
     * Produces a computed role's result deterministically (no model call). Currently supports
     * {@code classify-steps}: it classifies each step of the plan and records the classifications in the
     * audit trail. The role advances on a {@code continue} decision.
     */
    private RoleOutcome computeRole(Role role, String runId) {
        String text;
        if ("classify-steps".equalsIgnoreCase(role.compute())) {
            String plan = session.artifactContents().getOrDefault("plan", "");
            List<StepClassifier.Classified> steps = StepClassifier.classify(plan);
            if (steps.isEmpty()) {
                text = "No plan steps were found to classify.";
            } else {
                StringBuilder builder = new StringBuilder();
                for (int i = 0; i < steps.size(); i++) {
                    if (i > 0) builder.append('\n');
                    builder.append("Step ").append(i + 1).append(": ").append(steps.get(i).text())
                            .append(" -> ").append(steps.get(i).classification());
                }
                text = builder.toString();
            }
            audit.validation(runId, role.name(), "classified " + steps.size() + " step(s) deterministically");
        } else if ("build-topic-context".equalsIgnoreCase(role.compute())) {
            text = buildTopicContext(role, runId);
        } else if ("detail-todos".equalsIgnoreCase(role.compute())) {
            // The actual SubgoalPlanner calls happen in runTodoDetailing (it needs the model); here we just
            // summarise how many steps will be refined this pass.
            int todos = session.todos().size();
            text = todos == 0 ? "No steps need additional detail." : "Refining " + todos + " step(s).";
            audit.validation(runId, role.name(), "detailing " + todos + " step(s) flagged by PlanDetailReviewer");
        } else {
            text = "No engine built-in named '" + role.compute() + "'.";
        }
        return new RoleOutcome(text, new RoleFlowReply(text, "continue", ""), "");
    }

    /**
     * Deterministically gathers web context for EVERY topic identified by TopicAnalyzer (so no topic is
     * missed), combines it, and caches it on the session for downstream roles. Records the search of each
     * topic in the audit trail.
     */
    private String buildTopicContext(Role role, String runId) {
        List<String> topics = session.topics();
        if (topics.isEmpty()) {
            session.setWebContext("");
            return "No topics were identified, so no topic context was gathered.";
        }
        StringBuilder combined = new StringBuilder();
        for (String topic : topics) {
            String context = researcher == null ? "(web research is unavailable)" : researcher.context(topic);
            String details = context == null || context.isBlank() ? "(no details found)" : context;
            combined.append("## Topic: ").append(topic).append('\n').append(details).append("\n\n");
            // Record the actual details gathered (not just the topic name) so the audit trail shows what
            // was retrieved for each topic.
            audit.validation(runId, role.name(),
                    "gathered web context for topic '" + topic + "':\n" + details);
        }
        session.setWebContext(combined.toString().strip());
        return "Gathered context for " + topics.size() + " topic(s): " + String.join(", ", topics);
    }

    /**
     * For a role that CALLS a function per step (StepReviewer), classifies the plan's steps, and for each one
     * invokes the function mapped to its category. Each function call is a real model turn — recorded in the
     * audit trail exactly like a role (ROLE_STARTED, MODEL_REQUEST/RESPONSE, ROLE_RESULT, ARTIFACT_WRITTEN) —
     * that adds one detail section for that step to the plan file, then RETURNS to the caller.
     */
    private void runStepFunctions(Role caller, String runId, Integer maxTokens, Double temperature,
                                  ModelInvoker model) throws Exception {
        String plan = session.artifactContents().getOrDefault("plan", "");
        List<StepClassifier.Classified> steps = StepClassifier.classify(plan);
        for (int i = 0; i < steps.size(); i++) {
            StepClassifier.Classified step = steps.get(i);
            String functionName = caller.functionFor(step.classification());
            Role function = functionName == null ? null : config.byName(functionName);
            if (function == null) {
                continue; // no function mapped for this category; leave the step without an added section
            }
            String heading = "## Step " + (i + 1) + ": " + step.text() + " — " + step.classification();
            invokeFunction(caller, function, runId, heading, step.text(), step.classification(), null,
                    maxTokens, temperature, model);
        }
    }

    /**
     * For PlanDetailer ({@code Compute: detail-todos}), refines every step PlanDetailReviewer flagged as
     * needing more detail. For each TODO item it calls the mapped function (SubgoalPlanner) with an extra
     * instruction derived from the item's issue — the "additional prompt information" PlanDetailer provides —
     * so an ambiguous step gets decision points/assumptions and a too-high-level step gets a breakdown.
     */
    private void runTodoDetailing(Role caller, String runId, Integer maxTokens, Double temperature,
                                  ModelInvoker model) throws Exception {
        for (TodoItem todo : session.todos()) {
            String functionName = caller.functionFor(todo.issue());
            Role function = functionName == null ? null : config.byName(functionName);
            if (function == null) {
                continue;
            }
            String extra = TodoItem.TOO_HIGH_LEVEL.equals(todo.issue())
                    ? "This step is TOO HIGH-LEVEL: break it down into concrete substeps."
                    : "This step is AMBIGUOUS: add the decision points and assumptions it needs.";
            String heading = "## Refinement: " + todo.step() + " — " + todo.issue();
            invokeFunction(caller, function, runId, heading, todo.step(), todo.issue(), extra,
                    maxTokens, temperature, model);
        }
    }

    /**
     * Invokes one function for a single item, records it in the audit trail exactly like a role
     * (ROLE_STARTED, MODEL_REQUEST/RESPONSE, ROLE_RESULT, ARTIFACT_WRITTEN), appends its output as a section
     * to the plan file under {@code heading}, then records the RETURN to {@code caller}. {@code extraPrompt}
     * is optional additional instruction the calling role supplies for this item.
     */
    private void invokeFunction(Role caller, Role function, String runId, String heading, String itemText,
                                String itemCategory, String extraPrompt, Integer maxTokens, Double temperature,
                                ModelInvoker model) throws Exception {
        audit.roleStarted(runId, function.name());
        String systemPrompt = buildSystemPrompt(function, null) + stepContextSection(itemText, itemCategory);
        if (extraPrompt != null && !extraPrompt.isBlank()) {
            systemPrompt += "\nAdditional instruction for this step: " + extraPrompt;
        }
        RoleOutcome outcome = invokeRole(function, systemPrompt, session.topicPrompt(),
                maxTokens, temperature, model, runId);
        String detail = outcome.reply().message().isBlank() ? outcome.raw().trim() : outcome.reply().message();
        audit.roleResult(runId, function.name(), detail, outcome.reply().decision());

        String body = outcome.artifact().isBlank() ? detail : outcome.artifact();
        session.addStepSection(heading + "\n\n" + body.strip());
        rewritePlanWithStepSections(runId, function.name());
        // A function returns to its caller rather than transitioning; record the return like a transition.
        audit.transition(runId, function.name(), "return -> " + caller.name() + " (function returned)");
    }

    /** Recomposes the plan file to include the step-detail sections gathered so far and records the write. */
    private void rewritePlanWithStepSections(String runId, String byRole) throws java.io.IOException {
        String goal = session.artifactContents().get("goal");
        String plan = session.artifactContents().getOrDefault("plan", "");
        String document = PlanDocument.compose(goal, plan, session.stepSections());
        String path = writer.write("plan", runId, document);
        session.addArtifact("plan", plan, path); // plan content unchanged; same file, overwritten with detail
        audit.artifactWritten(runId, byRole, "plan", path);
    }

    /** The system-prompt section that tells a function which single item it is detailing. */
    private static String stepContextSection(String itemText, String category) {
        return "\n\nYou are detailing ONE specific item of the plan, shown below. Provide the detail for THIS "
                + "item only (not the whole plan), as described in your task, and put it in \"message\".\n"
                + "Describe it DIRECTLY. Do NOT refer to it by position and do NOT begin with phrases "
                + "like \"The first step is\" or \"This step\" — write the detail itself.\n"
                + "Item (classified as " + category + "): " + itemText;
    }

    /**
     * For a role that requests the run's topic context, returns a section to append to its system prompt.
     * The context is gathered up front by the TopicContextBuilder role (one web search per identified
     * topic); this method only injects what was already gathered — it performs no search of its own.
     */
    private String topicContextSection(Role role) {
        if (!role.researchesTopic()) {
            return "";
        }
        String context = session.webContext();
        if (context == null || context.isBlank()) {
            return "";
        }
        return "\n\nTopic context (general background gathered from the web — use it to interpret the prompt "
                + "correctly and to flag or fix any step that contradicts how the topic actually works):\n"
                + context;
    }

    /**
     * Returns the guidance for the skills a role declares that are RELEVANT to this run. A skill is relevant
     * when the run's identified topics include the skill's name (e.g. the {@code mathematics} skill applies
     * only when "mathematics" is an identified topic), so a role's domain knowledge is injected exactly when
     * the subject calls for it. Returns "" when the role declares no skills, none are registered, or none of
     * them match the run's topics.
     */
    private String skillsSection(Role role) {
        List<Skill> applied = applicableSkills(role);
        if (applied.isEmpty()) {
            return "";
        }
        StringBuilder section = new StringBuilder();
        for (Skill skill : applied) {
            section.append("### Skill: ").append(skill.name()).append('\n')
                    .append(skill.instructions()).append("\n\n");
        }
        return ("Apply the following skill guidance while performing this step:\n\n" + section).strip();
    }

    /**
     * The registered skills a role declares that are RELEVANT to this run — i.e. whose name matches one of
     * the run's identified topics. Returns an empty list when the role declares no skills, none are
     * registered, or none match the run's topics.
     */
    private List<Skill> applicableSkills(Role role) {
        if (skills == null || role.skills().isEmpty()) {
            return List.of();
        }
        List<String> topics = session.topics();
        List<Skill> applied = new ArrayList<>();
        for (String skillName : role.skills()) {
            Skill skill = skills.get(skillName);
            if (skill != null && topics.stream().anyMatch(topic -> topic.equalsIgnoreCase(skill.name()))) {
                applied.add(skill);
            }
        }
        return applied;
    }

    /** The artifact a role intends to write: its {@code artifact}, or its message for a mandatory role. */
    private static String effectiveArtifact(Role role, RoleFlowReply reply) {
        if (!role.hasOutput()) return "";
        String artifact = reply.artifact();
        if (artifact.isBlank() && role.outputMandatory()) artifact = reply.message();
        return artifact;
    }

    private static String planFeedback(List<String> problems) {
        StringBuilder feedback = new StringBuilder(
                "\n\nIMPORTANT: an automatic check REJECTED your previous reply for these reasons:\n");
        for (String problem : problems) feedback.append("- ").append(problem).append("\n");
        feedback.append("Rewrite the plan in \"artifact\" using EXACTLY these four headers, in order, ")
                .append("each followed by at least one '- ' step: '## Phase 1 - Preparation', ")
                .append("'## Phase 2 - Action', '## Phase 3 - Verification', '## Phase 4 - Next steps'. ")
                .append("Do not include a '# Goal' heading and do not copy the goal.");
        return feedback.toString();
    }

    /**
     * Finishes a run: appends the exact {@code file:///} links for any artifacts created (so the file
     * locations are authoritative rather than transcribed by the model), then resets the session. Must
     * read the artifacts before {@link RoleFlowSession#reset()} clears them.
     */
    private String complete(String lastMessage) {
        String message = withTopics(withArtifactLinks(lastMessage));
        session.reset();
        return message;
    }

    /** Appends the topics identified for the run to the final response, so the user sees them. */
    private String withTopics(String message) {
        List<String> topics = session.topics();
        if (topics.isEmpty()) {
            return message;
        }
        return (message == null ? "" : message) + "\n\nTopics considered: " + String.join(", ", topics);
    }

    private String withArtifactLinks(String message) {
        Map<String, String> locations = session.artifactPaths();
        if (locations.isEmpty()) {
            return message;
        }
        StringBuilder builder = new StringBuilder(message == null ? "" : message);
        builder.append("\n\nFiles created:");
        for (Map.Entry<String, String> entry : locations.entrySet()) {
            builder.append("\n- ").append(capitalize(entry.getKey())).append(" file: ").append(entry.getValue());
        }
        return builder.toString();
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) return value;
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    String buildSystemPrompt(Role role, String systemOverride) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are RoleFlow, an assistant that processes a user's prompt through a sequence ")
                .append("of roles. Perform ONLY the current role described below; later roles are handled ")
                .append("in separate steps.\n\n");
        prompt.append("Current role: ").append(role.name());
        if (role.title() != null && !role.title().isBlank()) {
            prompt.append(" (").append(role.title()).append(")");
        }
        prompt.append("\n\n");
        prompt.append("Task for this step:\n").append(role.action()).append("\n\n");
        String skillGuidance = skillsSection(role);
        if (!skillGuidance.isBlank()) {
            prompt.append(skillGuidance).append("\n\n");
        }
        prompt.append("Allowed values for \"decision\": ")
                .append(String.join(", ", role.allowedDecisions())).append("\n");
        if (role.hasOutput()) {
            prompt.append("This step produces a ").append(role.outputKind())
                    .append(" artifact: put its full human-readable content in \"artifact\".\n");
        }

        // A role that builds on or reviews prior artifacts (the plan uses the goal; the reviewers analyse
        // the plan) is shown their full content. This is independent of whether a file exists yet: the goal
        // has content but no file of its own, and PlanBuilder still needs to see it.
        Map<String, String> contents = session.artifactContents();
        if (role.needsArtifactContent() && !contents.isEmpty()) {
            prompt.append("\nContent produced so far in this request, for your reference ")
                    .append("(do not copy it verbatim into your reply):\n");
            for (Map.Entry<String, String> entry : contents.entrySet()) {
                prompt.append("--- ").append(entry.getKey()).append(" ---\n")
                        .append(entry.getValue()).append("\n");
            }
            // Include the per-step detail sections gathered so far, so a reviewer (PlanDetailReviewer) can
            // judge completeness and a function can avoid repeating detail that already exists.
            List<String> stepSections = session.stepSections();
            if (!stepSections.isEmpty()) {
                prompt.append("--- step details ---\n");
                for (String section : stepSections) {
                    prompt.append(section).append("\n\n");
                }
            }
        }

        Map<String, String> locations = session.artifactPaths();
        if (!locations.isEmpty()) {
            prompt.append("\nFiles already created in this request:\n");
            for (Map.Entry<String, String> entry : locations.entrySet()) {
                prompt.append("- ").append(entry.getKey()).append(" file: ").append(entry.getValue()).append("\n");
            }
            if (!role.needsArtifactContent()) {
                // A pure reporting role only needs the locations; withholding the content stops it from
                // pasting a file's body where its path belongs. The exact links are appended by the
                // engine when the run completes (see withArtifactLinks).
                prompt.append("Report these file locations to the user. ")
                        .append("Do not paste the file contents.\n");
            }
        }

        if (systemOverride != null && !systemOverride.isBlank()) {
            prompt.append("\nAdditional instructions from the user: ").append(systemOverride).append("\n");
        }

        prompt.append("\nRespond with ONLY a single JSON object and nothing else, in exactly this form:\n");
        prompt.append("{\"message\": \"<text to show the user>\", \"decision\": \"<one allowed decision>\", ")
                .append("\"artifact\": \"<file content, or empty string>\"}");
        return prompt.toString();
    }
}
