package com.example.orchestrator.policies;

import static com.example.orchestrator.core.PolicyEngine.Severity.APPROVAL;
import static com.example.orchestrator.core.PolicyEngine.Severity.BLOCK;
import static com.example.orchestrator.core.PolicyEngine.Severity.WARN;

import java.util.List;
import java.util.regex.Pattern;

import com.example.orchestrator.artifacts.Artifacts.ChangeSet;
import com.example.orchestrator.artifacts.Artifacts.Design;
import com.example.orchestrator.artifacts.Artifacts.EngineeringSummary;
import com.example.orchestrator.artifacts.Artifacts.Finding;
import com.example.orchestrator.artifacts.Artifacts.SecurityReport;
import com.example.orchestrator.artifacts.Artifacts.Spec;
import com.example.orchestrator.artifacts.Artifacts.TestReport;
import com.example.orchestrator.core.PolicyEngine.Rule;
import com.example.orchestrator.model.StageState;

/**
 * Guardrail rules, evaluated by the PolicyEngine when the listed stages finish.
 * Write-level guardrails (protected paths, workspace escape) are in {@link WriteGuard}.
 */
public final class SdlcPolicies {

    private static final int MAX_FILES_WITHOUT_REVIEW = 8;
    private static final int MAX_LINES_WITHOUT_REVIEW = 400;
    private static final Pattern PERSONAL_DATA_FIELD = Pattern.compile("private\\s+\\w+\\s+(ip|ipAddress|email|userAgent|phone|location|geo)\\s*;");

    private SdlcPolicies() {
    }

    public static List<Rule> rules() {
        return List.of(
                // ---------------- Requirements ----------------
                new Rule("REQ-001", "Ambiguous requirements need a product decision before design", "change-control", APPROVAL, "product-owner",
                        List.of("requirements"), ctx -> {
                            Spec spec = ctx.get("spec", Spec.class);
                            if (spec.ambiguities().isEmpty()) return null;
                            return "Unresolved: " + String.join("; ", spec.ambiguities().stream()
                                    .map(a -> "\"" + a.term() + "\" (" + a.question() + ")").toList());
                        }),

                // ---------------- Design ----------------
                new Rule("CHG-008", "Only automate changes that have a reviewed change plan", "change-control", BLOCK, null,
                        List.of("design"), ctx -> {
                            Design design = ctx.get("design", Design.class);
                            return design.automatable() ? null
                                    : "No automated change plan for this requirement. Handed off to a human: " + design.reason();
                        }),
                new Rule("CHG-001", "Large changes need tech-lead approval", "change-control", APPROVAL, "tech-lead",
                        List.of("design"), ctx -> {
                            Design design = ctx.get("design", Design.class);
                            if (!design.automatable()) return null;
                            int files = design.impactedFiles().size();
                            if (files > MAX_FILES_WITHOUT_REVIEW || design.estimatedLines() > MAX_LINES_WITHOUT_REVIEW) {
                                return "Change touches " + files + " files / ~" + design.estimatedLines() + " lines (limits "
                                        + MAX_FILES_WITHOUT_REVIEW + " / " + MAX_LINES_WITHOUT_REVIEW + ")";
                            }
                            return null;
                        }),

                // ---------------- Implementation ----------------
                new Rule("CMP-001", "New personal-data fields need a privacy/security review", "compliance", APPROVAL, "security-reviewer",
                        List.of("implementation"), ctx -> {
                            List<String> fields = ctx.get("changeSet", ChangeSet.class).addedLines().stream()
                                    .filter(l -> l.file().contains("/model/") && PERSONAL_DATA_FIELD.matcher(l.text()).find())
                                    .map(l -> l.text().trim()).toList();
                            return fields.isEmpty() ? null : "Document stores personal data: " + String.join(", ", fields);
                        }),
                new Rule("CMP-002", "Dependency changes need tech-lead approval", "compliance", APPROVAL, "tech-lead",
                        List.of("implementation"), ctx -> ctx.get("changeSet", ChangeSet.class).files().stream()
                                .anyMatch(f -> f.file().equals("pom.xml")) ? "pom.xml changed (new or upgraded dependency)" : null),
                new Rule("SEC-002", "Security-sensitive files are only changed after a security review", "security", BLOCK, null,
                        List.of("implementation"), ctx -> {
                            List<String> touched = ctx.get("changeSet", ChangeSet.class).files().stream()
                                    .map(f -> f.file()).filter(WriteGuard::isSecuritySensitive).toList();
                            if (touched.isEmpty()) return null;
                            StageState review = ctx.run().stages.get("securityReview");
                            return review != null && review.status == StageState.Status.COMPLETED ? null
                                    : "Changed " + String.join(", ", touched) + " without a completed securityReview stage";
                        }),

                // ---------------- Validation ----------------
                new Rule("SEC-001", "No high or critical security findings", "security", BLOCK, null,
                        List.of("security"), ctx -> {
                            List<Finding> serious = ctx.get("securityReport", SecurityReport.class).findings().stream()
                                    .filter(f -> f.severity().equals("high") || f.severity().equals("critical")).toList();
                            return serious.isEmpty() ? null : String.join("; ", serious.stream()
                                    .map(f -> f.rule() + " " + f.file() + ":" + f.line() + " " + f.message()).toList());
                        }),
                new Rule("SEC-003", "Dependency vulnerability audit should run", "security", WARN, null,
                        List.of("security"), ctx -> {
                            SecurityReport report = ctx.get("securityReport", SecurityReport.class);
                            return report.dependencyAudit().skipped() ? "Dependency audit skipped: " + report.dependencyAudit().reason() : null;
                        }),
                new Rule("CHG-005", "The number of tests must not go down", "change-control", BLOCK, null,
                        List.of("test"), ctx -> {
                            TestReport report = ctx.get("testReport", TestReport.class);
                            return report.testCount() < report.baselineTestCount()
                                    ? "Tests went from " + report.baselineTestCount() + " to " + report.testCount() : null;
                        }),

                // ---------------- Release readiness ----------------
                new Rule("REL-001", "Every acceptance criterion is traced to work and verified", "change-control", BLOCK, null,
                        List.of("readiness"), ctx -> {
                            List<String> untraced = ctx.get("engineeringSummary", EngineeringSummary.class).traceability().stream()
                                    .filter(t -> t.tasks().isEmpty()).map(t -> t.criterion()).toList();
                            return untraced.isEmpty() ? null : "Not traced: " + String.join(", ", untraced);
                        }));
    }
}
