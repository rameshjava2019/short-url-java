package com.example.orchestrator.workflow;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.orchestrator.artifacts.Artifacts.ChangeSet;
import com.example.orchestrator.artifacts.Artifacts.CompatibilityReport;
import com.example.orchestrator.artifacts.Artifacts.Design;
import com.example.orchestrator.artifacts.Artifacts.DocsReport;
import com.example.orchestrator.artifacts.Artifacts.EngineeringSummary;
import com.example.orchestrator.artifacts.Artifacts.RequirementSource;
import com.example.orchestrator.artifacts.Artifacts.Spec;
import com.example.orchestrator.artifacts.Artifacts.TestReport;
import com.example.orchestrator.core.Json;
import com.example.orchestrator.model.Run.PlanEntry;
import com.example.orchestrator.workflow.StageTemplate.ApprovalRequirement;

/**
 * The SDLC workflow: stage templates and the initial dependency graph.
 *
 * <pre>
 *   requirements → design → implementation ─┬─ test ─────┐
 *                                            ├─ security ─┼─ readiness → release
 *                                            └─ docs ─────┘
 * </pre>
 *
 * Agents may add two optional templates at run time (dynamic re-plan):
 * securityReview (human sign-off before implementation) and dataCompatibility
 * (backward-compatibility check when the MongoDB document changes).
 */
public final class SdlcWorkflow {

    private SdlcWorkflow() {
    }

    public static Workflow create() {
        Map<String, StageTemplate> templates = new LinkedHashMap<>();

        templates.put("requirements", StageTemplate.builder("Requirement analysis")
                .agent(options -> options.useLlm ? "requirements-llm" : "requirements")
                .fallbackAgent("requirements")
                .consumes("requirementSource")
                .produces("spec")
                .maxAttempts(2)
                .timeout(Duration.ofMinutes(3))
                .entryGate("requirement-present", ctx -> ctx.get("requirementSource", RequirementSource.class).text().isBlank()
                        ? "requirement document is empty" : null)
                .exitGate("spec-actionable", true, (result, ctx) -> {
                    Spec spec = as(result.artifact("spec"), Spec.class);
                    return spec.acceptanceCriteria().isEmpty() && spec.ambiguities().isEmpty()
                            ? "no acceptance criteria and no open questions found" : null;
                })
                .build());

        templates.put("design", StageTemplate.builder("Design and impact analysis")
                .agent("design")
                .consumes("spec", "requirements.clarification")
                .produces("design")
                .entryGate("ambiguities-resolved", ctx -> ctx.get("spec", Spec.class).ambiguities().isEmpty()
                        || ctx.has("requirements.clarification") ? null : "spec has unresolved ambiguities")
                .exitGate("criteria-traceable", false, (result, ctx) -> {
                    Design design = as(result.artifact("design"), Design.class);
                    if (!design.automatable()) return null; // handled by policy CHG-008
                    List<String> untraced = design.traceability().stream().filter(t -> t.tasks().isEmpty()).map(t -> t.criterion()).toList();
                    return untraced.isEmpty() ? null : "no task covers " + String.join(", ", untraced);
                })
                .build());

        templates.put("securityReview", StageTemplate.builder("Human security review")
                .agent("review-gate")
                .consumes("design")
                .produces("securityReviewRecord")
                .approvalBefore(ctx -> new ApprovalRequirement("security-reviewer",
                        "Plan changes security-sensitive files: " + String.join(", ", ctx.get("design", Design.class).sensitiveFiles())))
                .build());

        templates.put("implementation", StageTemplate.builder("Implementation in an isolated workspace")
                .agent("implementation")
                .consumes("design")
                .produces("changeSet")
                .sideEffects() // creates the workspace; rollback discards it
                .maxAttempts(2)
                .timeout(Duration.ofMinutes(5))
                .entryGate("design-automatable", ctx -> ctx.get("design", Design.class).automatable() ? null : "design is not automatable")
                .exitGate("compiles", false, (result, ctx) -> {
                    ChangeSet changes = as(result.artifact("changeSet"), ChangeSet.class);
                    return changes.compileErrors().isEmpty() ? null : String.join("; ", changes.compileErrors());
                })
                .exitGate("non-empty", false, (result, ctx) -> as(result.artifact("changeSet"), ChangeSet.class).files().isEmpty()
                        ? "no files changed" : null)
                .build());

        templates.put("dataCompatibility", StageTemplate.builder("Data compatibility check")
                .agent("compatibility")
                .consumes("changeSet")
                .produces("compatibilityReport")
                .exitGate("backward-compatible", false, (result, ctx) -> {
                    CompatibilityReport report = as(result.artifact("compatibilityReport"), CompatibilityReport.class);
                    return report.compatible() ? null : String.join("; ", report.issues());
                })
                .build());

        templates.put("test", StageTemplate.builder("Automated tests")
                .agent("test")
                .consumes("changeSet")
                .produces("testReport")
                .maxAttempts(3) // tests can be flaky; retries absorb that, and the audit trail shows it
                .timeout(Duration.ofMinutes(10))
                .exitGate("all-tests-pass", true, (result, ctx) -> {
                    TestReport report = as(result.artifact("testReport"), TestReport.class);
                    return report.failed() == 0 && report.total() > 0 ? null : report.failed() + " of " + report.total() + " tests failed";
                })
                .build());

        templates.put("security", StageTemplate.builder("Security scan")
                .agent("security")
                .fallbackAgent("security-offline") // static checks only, when the dependency audit is unavailable
                .consumes("changeSet")
                .produces("securityReport")
                .maxAttempts(2)
                .timeout(Duration.ofMinutes(5))
                .build());

        templates.put("docs", StageTemplate.builder("Documentation check")
                .agent("docs")
                .consumes("changeSet", "design")
                .produces("docsReport")
                .exitGate("docs-updated", false, (result, ctx) -> {
                    DocsReport report = as(result.artifact("docsReport"), DocsReport.class);
                    return report.missing().isEmpty() ? null : String.join("; ", report.missing().stream()
                            .map(m -> m.file() + " should mention \"" + m.contains() + "\"").toList());
                })
                .build());

        templates.put("readiness", StageTemplate.builder("Release readiness review")
                .agent("readiness")
                .consumes("spec", "requirements.clarification", "design", "changeSet", "testReport", "securityReport", "docsReport",
                        "compatibilityReport", "securityReviewRecord")
                .produces("engineeringSummary")
                .exitGate("verdict-ready", false, (result, ctx) -> {
                    EngineeringSummary summary = as(result.artifact("engineeringSummary"), EngineeringSummary.class);
                    return summary.verdict().equals("ready") ? null : "not ready: " + String.join("; ", summary.blockers());
                })
                .build());

        templates.put("release", StageTemplate.builder("Release (promote change)")
                .agent("release")
                .consumes("engineeringSummary", "changeSet")
                .produces("releaseReport")
                .sideEffects() // writes to the release target; rollback restores the backup
                .timeout(Duration.ofMinutes(10))
                .entryGate("readiness-verdict", ctx -> {
                    EngineeringSummary summary = ctx.get("engineeringSummary", EngineeringSummary.class);
                    return summary != null && summary.verdict().equals("ready") ? null : "readiness verdict is not \"ready\"";
                })
                .approvalBefore(ctx -> new ApprovalRequirement("release-manager",
                        "Promote " + ctx.get("changeSet", ChangeSet.class).files().size() + " changed file(s) to "
                                + ("project".equals(ctx.options().releaseTo) ? "the project" : "the sandbox release target")
                                + ". Review " + ctx.get("engineeringSummary", EngineeringSummary.class).markdownFile()))
                .build());

        List<PlanEntry> initialPlan = List.of(
                new PlanEntry("requirements", "requirements", List.of(), null),
                new PlanEntry("design", "design", List.of("requirements"), null),
                new PlanEntry("implementation", "implementation", List.of("design"), null),
                new PlanEntry("test", "test", List.of("implementation"), null),
                new PlanEntry("security", "security", List.of("implementation"), null),
                new PlanEntry("docs", "docs", List.of("implementation"), null),
                new PlanEntry("readiness", "readiness", List.of("test", "security", "docs"), null),
                new PlanEntry("release", "release", List.of("readiness"), null));

        return new Workflow(templates, initialPlan, Set.of("securityReview", "dataCompatibility"));
    }

    // Agent results hold the agent's own objects; gates read them through JSON so any type works.
    private static <T> T as(Object value, Class<T> type) {
        return Json.convert(value, type);
    }
}
