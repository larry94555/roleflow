package com.example.roleflow;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads and holds the workflow defined in {@code roleflow.active}. The file lists roles, each a number
 * and a name followed by {@code Role}, {@code Action}, {@code Output}, and {@code Transition} fields.
 * When no roles are loaded (file missing or empty), {@link #isActive()} is false and the application
 * falls back to plain single-call behavior.
 */
@Component
public class RoleFlowConfig {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RoleFlowConfig.class);

    private static final Pattern ROLE_HEADER = Pattern.compile("^\\s*(\\d+)\\.\\s+([A-Za-z][\\w-]*)\\s*$");
    private static final Pattern FIELD_HEADER =
            Pattern.compile("^\\s*(Role|Action|Output|Reads|Compute|Research|Provides|Skills|Transition)\\s*:(.*)$");

    private final List<Role> roles;
    private final Map<String, Role> byName;

    @Autowired
    public RoleFlowConfig(@Value("${roleflow.config:config/roleflow.active}") String configPath) {
        this(load(configPath));
    }

    RoleFlowConfig(List<Role> roles) {
        this.roles = List.copyOf(roles);
        Map<String, Role> index = new LinkedHashMap<>();
        for (Role role : roles) {
            index.put(role.name().toLowerCase(Locale.ROOT), role);
        }
        this.byName = index;
    }

    private static List<Role> load(String configPath) {
        Path path = Path.of(configPath);
        if (!Files.isRegularFile(path)) {
            log.warn("[roleflow] no active config at {}; running in plain single-call mode", path.toAbsolutePath());
            return List.of();
        }
        try {
            List<Role> parsed = parse(Files.readString(path));
            log.info("[roleflow] loaded {} roles from {}", parsed.size(), path);
            return parsed;
        } catch (Exception e) {
            log.warn("[roleflow] failed to read {}: {}", path, e.getMessage());
            return List.of();
        }
    }

    /** Parses the {@code roleflow.active} text into ordered roles. */
    public static List<Role> parse(String text) {
        List<Role> result = new ArrayList<>();
        String[] lines = text.split("\\R");

        Integer number = null;
        String name = null;
        String field = null;
        Map<String, StringBuilder> fields = new LinkedHashMap<>();

        for (String line : lines) {
            Matcher header = ROLE_HEADER.matcher(line);
            if (header.matches()) {
                flush(result, number, name, fields);
                number = Integer.parseInt(header.group(1));
                name = header.group(2);
                field = null;
                fields = new LinkedHashMap<>();
                continue;
            }
            if (number == null) {
                continue; // preamble / comments before the first role
            }
            if (line.stripLeading().startsWith("#")) {
                continue;
            }
            Matcher fieldHeader = FIELD_HEADER.matcher(line);
            if (fieldHeader.matches()) {
                field = fieldHeader.group(1).toLowerCase(Locale.ROOT);
                fields.put(field, new StringBuilder(fieldHeader.group(2).trim()));
            } else if (field != null) {
                fields.get(field).append('\n').append(line);
            }
        }
        flush(result, number, name, fields);
        return result;
    }

    private static void flush(List<Role> result, Integer number, String name,
                              Map<String, StringBuilder> fields) {
        if (number == null || name == null) return;
        String title = dedent(value(fields, "role"));
        String action = dedent(value(fields, "action"));

        // Output field: "<kind>" or "none", optionally followed by "conditional" (write only when the
        // model supplies an artifact; do not fall back to the message).
        String outputKind = null;
        boolean outputMandatory = true;
        String[] outputTokens = value(fields, "output").trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (outputTokens.length > 0 && !outputTokens[0].isBlank() && !"none".equals(outputTokens[0])) {
            outputKind = outputTokens[0];
        }
        for (String token : outputTokens) {
            if ("conditional".equals(token)) outputMandatory = false;
        }

        // Reads field: present and non-blank (and not "none") means the role needs prior artifact content.
        String reads = value(fields, "reads").trim();
        boolean readsArtifacts = !reads.isBlank() && !"none".equalsIgnoreCase(reads);

        // Compute field: names an engine built-in that produces the role's result deterministically.
        String compute = value(fields, "compute").trim();
        if (compute.isBlank() || "none".equalsIgnoreCase(compute)) compute = null;

        // Research field: present and non-blank (and not "none") means include the run's topic context.
        String research = value(fields, "research").trim();
        boolean researchesTopic = !research.isBlank() && !"none".equalsIgnoreCase(research);

        // Provides field: what the role's output feeds the engine (e.g. "topics").
        String provides = value(fields, "provides").trim();
        if (provides.isBlank() || "none".equalsIgnoreCase(provides)) provides = null;

        // Skills field: comma/space-separated names of skills the role may apply (e.g. "mathematics").
        List<String> skills = parseSkills(value(fields, "skills"));

        List<Role.Transition> transitions = parseTransitions(value(fields, "transition"));
        result.add(new Role(number, name, title, action, outputKind, outputMandatory, readsArtifacts,
                compute, researchesTopic, provides, skills, transitions));
    }

    /** Parses a comma/semicolon/whitespace-separated list of skill names, dropping blanks and "none". */
    private static List<String> parseSkills(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> skills = new ArrayList<>();
        for (String part : text.split("[,;\\s]+")) {
            String name = part.trim();
            if (!name.isEmpty() && !"none".equalsIgnoreCase(name)) skills.add(name);
        }
        return List.copyOf(skills);
    }

    private static List<Role.Transition> parseTransitions(String text) {
        List<Role.Transition> transitions = new ArrayList<>();
        for (String part : text.split("[,;\\n]")) {
            String rule = part.trim();
            if (rule.isEmpty()) continue;
            int arrow = rule.indexOf("->");
            if (arrow >= 0) {
                String label = rule.substring(0, arrow).trim();
                String target = rule.substring(arrow + 2).trim();
                transitions.add(new Role.Transition(label.isEmpty() ? null : label, target));
            } else {
                // No label: an unconditional transition (e.g. "done" or a bare role name).
                transitions.add(new Role.Transition(null, rule));
            }
        }
        return transitions;
    }

    private static String value(Map<String, StringBuilder> fields, String key) {
        StringBuilder builder = fields.get(key);
        return builder == null ? "" : builder.toString();
    }

    /** Trims surrounding blank lines and strips common leading whitespace from each line. */
    private static String dedent(String text) {
        String[] lines = text.split("\n");
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.strip();
            if (out.length() == 0 && trimmed.isEmpty()) continue; // skip leading blanks
            out.append(trimmed).append('\n');
        }
        return out.toString().strip();
    }

    public boolean isActive() {
        return !roles.isEmpty();
    }

    public List<Role> roles() {
        return roles;
    }

    public Role firstRole() {
        return roles.isEmpty() ? null : roles.get(0);
    }

    public Role byName(String roleName) {
        return roleName == null ? null : byName.get(roleName.toLowerCase(Locale.ROOT));
    }
}
