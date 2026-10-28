package com.example.orchestrator.agents;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.example.orchestrator.agents.lib.Codebase;
import com.example.orchestrator.agents.lib.Exec;
import com.example.orchestrator.artifacts.Artifacts.ChangeSet;
import com.example.orchestrator.artifacts.Artifacts.DependencyAudit;
import com.example.orchestrator.artifacts.Artifacts.FileChange;
import com.example.orchestrator.artifacts.Artifacts.Finding;
import com.example.orchestrator.artifacts.Artifacts.SecurityReport;
import com.example.orchestrator.core.Json;
import com.example.orchestrator.core.OrchestratorException;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Security scan. Full mode = static checks on every changed file + an audit of
 * the runtime dependencies against the OSV.dev vulnerability database (needs
 * network). {@link #offline()} returns the static-only fallback.
 */
public final class SecurityAgent implements Agent {

    private record Rule(String id, String severity, Pattern pattern, Predicate<String> appliesTo, String message) {
    }

    private static final List<Rule> STATIC_RULES = List.of(
            new Rule("SAST-001", "high", Pattern.compile("Runtime\\.getRuntime\\(\\)\\.exec\\(|new ProcessBuilder\\("),
                    f -> f.startsWith("src/main/"), "shell access from application code"),
            new Rule("SAST-002", "critical", Pattern.compile("AKIA[0-9A-Z]{16}|-----BEGIN (RSA |EC )?PRIVATE KEY-----"),
                    f -> true, "hard-coded credential"),
            new Rule("SAST-003", "high", Pattern.compile("(?i)(password|secret|api[_-]?key|token)\\s*[=:]\\s*\"[^\"\\s]{12,}\""),
                    f -> f.startsWith("src/main/"), "possible hard-coded secret"),
            new Rule("SAST-004", "high", Pattern.compile("th:utext"),
                    f -> f.startsWith("src/main/resources/templates/"), "unescaped Thymeleaf output (XSS risk); use th:text"),
            new Rule("SAST-005", "medium", Pattern.compile("new Random\\(|Math\\.random\\("),
                    f -> f.startsWith("src/main/java/"), "non-cryptographic randomness"),
            new Rule("SAST-006", "high", Pattern.compile("\"redirect:\"\\s*\\+\\s*\\w*\\.?getParameter|\"redirect:\"\\s*\\+\\s*request"),
                    f -> f.startsWith("src/main/java/"), "open redirect from request input"));

    private static final String SECURITY_CONFIG = "src/main/java/com/example/shorturl/config/SecurityConfig.java";
    private static final Pattern CSRF_DISABLED = Pattern.compile("csrf\\s*\\(\\s*\\w+\\s*->\\s*\\w+\\.disable\\(\\)|csrf\\(\\)\\s*\\.disable\\(\\)|AbstractHttpConfigurer::disable");

    private final boolean dependencyAudit;

    public SecurityAgent() {
        this(true);
    }

    private SecurityAgent(boolean dependencyAudit) {
        this.dependencyAudit = dependencyAudit;
    }

    /** Fallback when the dependency audit is unavailable (e.g. offline): static checks only. */
    public static SecurityAgent offline() {
        return new SecurityAgent(false);
    }

    @Override
    public String name() {
        return dependencyAudit ? "security" : "security-offline";
    }

    @Override
    public AgentResult run(AgentContext context) {
        ChangeSet changeSet = context.ctx().get("changeSet", ChangeSet.class);
        Path workspace = Path.of(changeSet.workspace());
        List<Finding> findings = staticScan(workspace, changeSet.files());

        if (!dependencyAudit) {
            context.log("dependency audit unavailable; running static checks only");
            return AgentResult.of("securityReport", new SecurityReport("static-only", findings,
                    new DependencyAudit(true, "dependency audit unavailable; fell back to static analysis", 0, List.of()))).summary("static checks only");
        }

        context.log("listing runtime dependencies (mvn dependency:list)");
        List<String[]> dependencies = listDependencies(workspace, context);
        context.log("checking " + dependencies.size() + " dependencies against OSV.dev");
        List<String> vulnerable = new ArrayList<>();
        findings.addAll(auditWithOsv(dependencies, vulnerable));

        return AgentResult.of("securityReport", new SecurityReport("full", findings, new DependencyAudit(false, null, dependencies.size(), vulnerable)))
                .summary(findings.size() + " findings; " + vulnerable.size() + " vulnerable dependencies");
    }

    static List<Finding> staticScan(Path workspace, List<FileChange> changedFiles) {
        List<Finding> findings = new ArrayList<>();
        for (FileChange change : changedFiles) {
            Path file = workspace.resolve(change.file());
            if (!Files.exists(file)) continue;
            List<String> lines = Codebase.read(file).lines().toList();
            for (Rule rule : STATIC_RULES) {
                if (!rule.appliesTo().test(change.file())) continue;
                for (int i = 0; i < lines.size(); i++) {
                    if (rule.pattern().matcher(lines.get(i)).find()) {
                        findings.add(new Finding(rule.id(), rule.severity(), change.file(), i + 1, rule.message()));
                    }
                }
            }
        }

        // Invariants of this codebase that must survive any change.
        Path config = workspace.resolve(SECURITY_CONFIG);
        String securityConfig = Files.exists(config) ? Codebase.read(config) : "";
        if (!securityConfig.contains(".csrf(") || CSRF_DISABLED.matcher(securityConfig).find()) {
            findings.add(new Finding("INV-001", "high", SECURITY_CONFIG, 0, "CSRF protection removed or disabled for the UI"));
        }
        if (!securityConfig.contains("contentSecurityPolicy")) {
            findings.add(new Finding("INV-002", "high", SECURITY_CONFIG, 0, "Content-Security-Policy header removed"));
        }
        return findings;
    }

    private static List<String[]> listDependencies(Path workspace, AgentContext context) {
        Path output = workspace.resolve("target/dependencies.txt");
        Exec.Result result = Exec.maven(workspace, Duration.ofMinutes(3), context.signal(), "dependency:list", "-DincludeScope=runtime",
                "-DoutputFile=" + output, "-DexcludeTransitive=false");
        if (result.exitCode() != 0 || !Files.exists(output)) {
            throw new OrchestratorException("mvn dependency:list failed: " + Exec.tail(result.output(), 3));
        }
        List<String[]> coordinates = new ArrayList<>();
        for (String line : Codebase.read(output).lines().map(String::trim).toList()) {
            String[] parts = line.split(":");
            // group:artifact:type[:classifier]:version:scope
            if (parts.length >= 5) coordinates.add(new String[] {parts[0] + ":" + parts[1], parts[parts.length - 2]});
        }
        return coordinates;
    }

    private static List<Finding> auditWithOsv(List<String[]> dependencies, List<String> vulnerable) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        List<Map<String, Object>> queries = dependencies.stream()
                .map(d -> Map.<String, Object>of("package", Map.of("name", d[0], "ecosystem", "Maven"), "version", d[1])).toList();
        JsonNode batch = post(http, "https://api.osv.dev/v1/querybatch", Map.of("queries", queries));

        List<Finding> findings = new ArrayList<>();
        Map<String, String> severityCache = new LinkedHashMap<>();
        for (int i = 0; i < dependencies.size(); i++) {
            JsonNode vulns = batch.path("results").path(i).path("vulns");
            if (!vulns.isArray() || vulns.isEmpty()) continue;
            String coordinate = dependencies.get(i)[0] + ":" + dependencies.get(i)[1];
            for (JsonNode vuln : vulns) {
                String id = vuln.path("id").asText();
                String severity = severityCache.computeIfAbsent(id, key -> severityOf(http, key));
                vulnerable.add(coordinate + " " + id + " (" + severity + ")");
                findings.add(new Finding("DEP-001", severity, "pom.xml", 0, "vulnerable dependency " + coordinate + ": " + id));
            }
        }
        return findings;
    }

    // GitHub advisories in OSV carry a severity label; anything else is treated as medium.
    private static String severityOf(HttpClient http, String vulnId) {
        JsonNode vuln = get(http, "https://api.osv.dev/v1/vulns/" + vulnId);
        String label = vuln.path("database_specific").path("severity").asText("MODERATE").toLowerCase(Locale.ROOT);
        return switch (label) {
            case "critical" -> "critical";
            case "high" -> "high";
            case "low" -> "low";
            default -> "medium";
        };
    }

    private static JsonNode post(HttpClient http, String url, Object body) {
        return send(http, HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Json.toTree(body).toString())).build());
    }

    private static JsonNode get(HttpClient http, String url) {
        return send(http, HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build());
    }

    private static JsonNode send(HttpClient http, HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new OrchestratorException("OSV.dev returned HTTP " + response.statusCode());
            return Json.MAPPER.readTree(response.body());
        } catch (OrchestratorException e) {
            throw e;
        } catch (Exception e) {
            throw new OrchestratorException("OSV.dev unavailable: " + e.getMessage()); // retryable, then fallback
        }
    }
}
