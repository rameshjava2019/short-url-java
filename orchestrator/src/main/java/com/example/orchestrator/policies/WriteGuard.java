package com.example.orchestrator.policies;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import com.example.orchestrator.core.OrchestratorException;

/**
 * Checks every file write an agent makes (CHG-003 / CHG-004): paths must be
 * relative, stay inside the workspace, and not be protected.
 */
public final class WriteGuard {

    private record Protected(Pattern pattern, String why) {
    }

    // Files agents must never write, whatever the change plan says.
    private static final List<Protected> PROTECTED_PATHS = List.of(
            new Protected(Pattern.compile("^\\.env(\\..*)?$"), "holds real secrets"),
            new Protected(Pattern.compile("^(mvnw|mvnw\\.cmd|\\.mvn/.*)$"), "build tooling is changed only under review"),
            new Protected(Pattern.compile("^target/.*"), "build output"),
            new Protected(Pattern.compile("^\\.git/.*"), "version control internals"),
            new Protected(Pattern.compile("^\\.orchestrator/.*"), "run state and audit trail"),
            new Protected(Pattern.compile("^orchestrator/.*"), "agents may not change their own governance (policies, engine, approvers)"),
            new Protected(Pattern.compile("^orchestrate$"), "agents may not change their own launcher"),
            new Protected(Pattern.compile("^\\.claude/.*"), "tooling configuration"));

    /** Changing these weakens security if done wrong, so they need a human security review. */
    public static final List<String> SECURITY_SENSITIVE_PATHS = List.of(
            "src/main/java/com/example/shorturl/config/SecurityConfig.java",
            "src/main/java/com/example/shorturl/config/AppProperties.java",
            "src/main/resources/application.yml",
            "pom.xml");

    private WriteGuard() {
    }

    /** Returns the normalized relative path, or throws a policy violation. */
    public static String assertWriteAllowed(String relativePath) {
        String unixPath = relativePath.replace('\\', '/');
        if (unixPath.startsWith("/") || Path.of(relativePath).isAbsolute()) {
            throw new OrchestratorException.PolicyViolation("CHG-003", "Absolute paths are not allowed: " + relativePath);
        }
        String normalized = Path.of(unixPath).normalize().toString().replace('\\', '/');
        if (normalized.equals("..") || normalized.startsWith("../")) {
            throw new OrchestratorException.PolicyViolation("CHG-003", "Path escapes the workspace: " + relativePath);
        }
        for (Protected rule : PROTECTED_PATHS) {
            if (rule.pattern().matcher(normalized).matches()) {
                throw new OrchestratorException.PolicyViolation("CHG-004", "\"" + normalized + "\" is protected (" + rule.why() + ")");
            }
        }
        return normalized;
    }

    public static boolean isSecuritySensitive(String relativePath) {
        return SECURITY_SENSITIVE_PATHS.contains(relativePath);
    }
}
