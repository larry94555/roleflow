package com.example.roleflow;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks where the workflow is for the current session: which role to (re)enter next, the id shared by
 * the goal/plan files of the request being processed, and the artifacts produced so far in that request.
 *
 * <p>A run starts at the first role; it pauses (keeping {@link #currentRole()}) when a role asks the user
 * a clarifying question, and resets to idle when the run completes. A fresh session begins each time the
 * application restarts. Access is serialized by the caller (the conversation lock).
 */
@Component
public class RoleFlowSession {

    private String currentRole;
    private String runId;
    private String topicPrompt;
    private String webContext;
    private List<String> topics = new ArrayList<>();
    private final Map<String, String> artifactContents = new LinkedHashMap<>();
    private final Map<String, String> artifactPaths = new LinkedHashMap<>();

    /** True when no run is in progress, so the next prompt should start a fresh run. */
    public boolean isIdle() {
        return currentRole == null;
    }

    /**
     * Starts a new run at the given role. {@code topicPrompt} is the request that started the run (used,
     * for example, as the query when a reviewing role fetches web context); it persists across clarification
     * pauses.
     */
    public void begin(String firstRole, String runId, String topicPrompt) {
        this.currentRole = firstRole;
        this.runId = runId;
        this.topicPrompt = topicPrompt;
        this.webContext = null;
        this.topics = new ArrayList<>();
        artifactContents.clear();
        artifactPaths.clear();
    }

    /** Advances to the next role within the current run. */
    public void moveTo(String role) {
        currentRole = role;
    }

    /** Ends the run; the next prompt starts over at the first role. */
    public void reset() {
        currentRole = null;
        runId = null;
        topicPrompt = null;
        webContext = null;
        topics = new ArrayList<>();
        artifactContents.clear();
        artifactPaths.clear();
    }

    /** The topics identified for this run (by TopicAnalyzer); empty when none were found. */
    public List<String> topics() {
        return new ArrayList<>(topics);
    }

    public void setTopics(List<String> topics) {
        this.topics = topics == null ? new ArrayList<>() : new ArrayList<>(topics);
    }

    /** The request that started the run (the topic), persisted across clarification pauses. */
    public String topicPrompt() {
        return topicPrompt;
    }

    /** Web context fetched for the topic this run, cached so it is fetched at most once. Null until set. */
    public String webContext() {
        return webContext;
    }

    public void setWebContext(String webContext) {
        this.webContext = webContext;
    }

    public void addArtifact(String kind, String content, String path) {
        artifactContents.put(kind, content);
        artifactPaths.put(kind, path);
    }

    public String currentRole() {
        return currentRole;
    }

    public String runId() {
        return runId;
    }

    public Map<String, String> artifactContents() {
        return new LinkedHashMap<>(artifactContents);
    }

    public Map<String, String> artifactPaths() {
        return new LinkedHashMap<>(artifactPaths);
    }
}
