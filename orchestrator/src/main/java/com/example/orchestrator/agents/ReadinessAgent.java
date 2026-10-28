package com.example.orchestrator.agents;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.example.orchestrator.artifacts.Artifacts.ChangeSet;
import com.example.orchestrator.artifacts.Artifacts.Check;
import com.example.orchestrator.artifacts.Artifacts.Clarification;
import com.example.orchestrator.artifacts.Artifacts.CompatibilityReport;
import com.example.orchestrator.artifacts.Artifacts.Design;
import com.example.orchestrator.artifacts.Artifacts.DocsReport;
import com.example.orchestrator.artifacts.Artifacts.EngineeringSummary;
import com.example.orchestrator.artifacts.Artifacts.SecurityReport;
import com.example.orchestrator.artifacts.Artifacts.SecurityReviewRecord;
import com.example.orchestrator.artifacts.Artifacts.Spec;
import com.example.orchestrator.artifacts.Artifacts.TestReport;
import com.example.orchestrator.core.RunContext;
import com.example.orchestrator.model.Approval;
import com.example.orchestrator.model.Run;

/**
 * Release readiness: collects every upstream result into a verdict and writes the
 * Final Engineering Summary (plan, rationale, artifacts, validation, risks,
 * assumptions, limitations, decision lineage) for the release approver.
 */
public final class ReadinessAgent implements Agent {

    @Override
    public String name() {
        return "readiness";
    }

    @Override
    public AgentResult run(AgentContext context) {
        RunContext ctx = context.ctx();
        Spec spec = ctx.get("spec", Spec.class);
        Design design = ctx.get("design", Design.class);
        ChangeSet changes = ctx.get("changeSet", ChangeSet.class);
        TestReport tests = ctx.get("testReport", TestReport.class);
        SecurityReport security = ctx.get("securityReport", SecurityReport.class);
        DocsReport docs = ctx.get("docsReport", DocsReport.class);
        CompatibilityReport compatibility = ctx.get("compatibilityReport", CompatibilityReport.class);
        SecurityReviewRecord review = ctx.get("securityReviewRecord", SecurityReviewRecord.class);
        Clarification clarification = ctx.get("requirements.clarification", Clarification.class);

        List<Check> checks = new ArrayList<>();
        checks.add(new Check("Tests", tests.failed() == 0,
                tests.passed() + "/" + tests.total() + " passed, test methods " + tests.baselineTestCount() + " → " + tests.testCount()));
        checks.add(new Check("Security", security.findings().stream().noneMatch(f -> f.severity().equals("high") || f.severity().equals("critical")),
                security.findings().size() + " finding(s), mode: " + security.mode()));
        checks.add(new Check("Documentation", docs.missing().isEmpty(),
                (docs.expectations().size() - docs.missing().size()) + "/" + docs.expectations().size() + " expectations met"));
        if (compatibility != null) {
            checks.add(new Check("Data compatibility", compatibility.compatible(), compatibility.issues().isEmpty()
                    ? "new fields: " + String.join(", ", compatibility.newFields()) : String.join("; ", compatibility.issues())));
        }
        if (!design.sensitiveFiles().isEmpty()) {
            checks.add(new Check("Security review", review != null && review.approvedBy() != null,
                    review == null ? "missing" : "signed off by " + review.approvedBy()));
        }

        List<String> blockers = checks.stream().filter(c -> !c.passed()).map(c -> c.check() + ": " + c.detail()).toList();
        String verdict = blockers.isEmpty() ? "ready" : "not-ready";
        Path markdown = ctx.runDir().resolve("ENGINEERING_SUMMARY.md");
        write(markdown, render(ctx, spec, design, changes, tests, security, docs, compatibility, clarification, checks, verdict));

        return AgentResult.of("engineeringSummary", new EngineeringSummary(verdict, blockers, checks, design.traceability(), markdown.toString()))
                .decision("Readiness verdict: " + verdict, blockers.isEmpty() ? "All validation checks passed" : String.join("; ", blockers))
                .summary(verdict);
    }

    private static String render(RunContext ctx, Spec spec, Design design, ChangeSet changes, TestReport tests, SecurityReport security,
            DocsReport docs, CompatibilityReport compatibility, Clarification clarification, List<Check> checks, String verdict) {
        Run run = ctx.run();
        StringBuilder md = new StringBuilder();
        synchronized (run) {
            md.append("# Engineering Summary: ").append(spec.title()).append("\n\n");
            md.append("Run `").append(run.id).append("` · scenario `").append(run.scenarioId).append("` · generated ").append(Instant.now()).append("\n\n");
            md.append("**Readiness verdict: ").append(verdict.toUpperCase()).append("**\n\n");

            md.append("## 1. Requirement understanding\n\n").append(spec.summary()).append("\n\nAnalyzed by: ").append(spec.analyzer()).append("\n\n");
            md.append("| ID | Acceptance criterion | Source |\n|---|---|---|\n");
            design.criteria().forEach(c -> md.append("| ").append(c.id()).append(" | ").append(cell(c.text())).append(" | ")
                    .append(c.refined() ? "refined after clarification" : c.derived() ? "derived" : "stated").append(" |\n"));
            if (spec.ambiguities().isEmpty()) {
                md.append("\nNo ambiguities found.\n\n");
            } else {
                md.append("\n**Ambiguities found:** ").append(spec.ambiguities().stream().map(a -> a.question()).collect(Collectors.joining(" ")))
                        .append("\n\n**Clarification:** ").append(clarification == null ? "none"
                                : "\"" + clarification.choice() + "\" chosen by " + clarification.decidedBy()).append("\n\n");
            }

            md.append("## 2. Plan and rationale\n\n**").append(design.title()).append("**\n\n").append(design.approach()).append("\n\nTasks:\n\n");
            design.tasks().forEach(t -> md.append("- ").append(t.id()).append(": ").append(t.title())
                    .append(t.dependsOn().isEmpty() ? "" : " (after " + String.join(", ", t.dependsOn()) + ")").append('\n'));
            md.append("\nTraceability:\n\n| Criterion | Covered by |\n|---|---|\n");
            design.traceability().forEach(t -> md.append("| ").append(t.criterion()).append(" | ")
                    .append(t.tasks().isEmpty() ? "**none**" : String.join(", ", t.tasks())).append(" |\n"));

            md.append("\n## 3. Impact analysis\n\n| File | Change | Used by | Security-sensitive |\n|---|---|---|---|\n");
            design.impactedFiles().forEach(f -> md.append("| ").append(f.file()).append(" | ").append(f.change()).append(" | ")
                    .append(f.dependents().isEmpty() ? "n/a" : String.join(", ", f.dependents())).append(" | ")
                    .append(f.securitySensitive() ? "yes" : "no").append(" |\n"));
            md.append("\nRelated files reviewed: ").append(design.relatedFiles().isEmpty() ? "none"
                    : design.relatedFiles().stream().map(r -> r.file()).collect(Collectors.joining(", "))).append("\n\n");
            String added = run.plan.stream().filter(e -> e.addedBy != null).map(e -> "`" + e.id + "` (added by " + e.addedBy + ")")
                    .collect(Collectors.joining(", "));
            md.append("Workflow changes made by the plan: ").append(added.isEmpty() ? "none" : added).append("\n\n");

            md.append("## 4. Artifacts\n\n- Change set: ").append(changes.totals().files()).append(" files, +").append(changes.totals().added())
                    .append(" / -").append(changes.totals().removed()).append(" lines\n- Patch for review: `").append(changes.patchFile())
                    .append("`\n- Workspace: `").append(changes.workspace()).append("`\n\n| File | Change | + | - |\n|---|---|---|---|\n");
            changes.files().forEach(f -> md.append("| ").append(f.file()).append(" | ").append(f.change()).append(" | ").append(f.added())
                    .append(" | ").append(f.removed()).append(" |\n"));

            md.append("\n## 5. Validation\n\n| Check | Result | Detail |\n|---|---|---|\n");
            checks.forEach(c -> md.append("| ").append(c.check()).append(" | ").append(c.passed() ? "pass" : "**FAIL**").append(" | ")
                    .append(cell(c.detail())).append(" |\n"));
            if (!tests.failures().isEmpty()) {
                md.append("\nFailing tests:\n\n");
                tests.failures().forEach(f -> md.append("- ").append(f).append('\n'));
            }
            md.append("\nSecurity findings: ").append(security.findings().isEmpty() ? "none" : "").append('\n');
            security.findings().forEach(f -> md.append("- [").append(f.severity()).append("] ").append(f.rule()).append(' ').append(f.file())
                    .append(':').append(f.line()).append(' ').append(f.message()).append('\n'));
            md.append("\nDependency audit: ").append(security.dependencyAudit().skipped() ? "skipped (" + security.dependencyAudit().reason() + ")"
                    : security.dependencyAudit().checked() + " runtime dependencies checked against OSV.dev, "
                            + security.dependencyAudit().vulnerable().size() + " with known vulnerabilities").append("\n\n");
            if (compatibility != null) {
                md.append("Data compatibility: ").append(compatibility.compatible() ? "backward compatible, no migration needed"
                        : String.join("; ", compatibility.issues())).append(compatibility.notes().isEmpty() ? "" : " (" + String.join("; ", compatibility.notes()) + ")")
                        .append("\n\n");
            }
            md.append("Documentation: ").append(docs.expectations().stream()
                    .map(e -> (Boolean.TRUE.equals(e.met()) ? "✔ " : "✖ ") + e.file() + " mentions \"" + e.contains() + "\"").collect(Collectors.joining(" · ")))
                    .append("\n\n### Policy guardrails\n\n| Stage | Rule | Category | Severity | Result |\n|---|---|---|---|---|\n");
            run.policyResults.forEach((stage, results) -> results.forEach(r -> md.append("| ").append(stage).append(" | ").append(r.ruleId())
                    .append(" | ").append(r.category()).append(" | ").append(r.severity()).append(" | ")
                    .append(r.passed() ? "pass" : "**" + cell(r.message()) + "**").append(" |\n")));

            md.append("\n## 6. Risks and trade-offs\n\n");
            design.risks().forEach(r -> md.append("- ").append(r).append('\n'));
            md.append("\n## 7. Assumptions\n\n");
            design.assumptions().forEach(a -> md.append("- ").append(a).append('\n'));
            md.append("""

                    ## 8. Limitations

                    - Change plans are written and reviewed by engineers; the agents select, apply, verify and document them. They do not invent code.
                    - Tests run against an embedded MongoDB; production data volume and index build time are not exercised.
                    - Static security rules are pattern-based and can miss issues a human reviewer would catch.

                    ## 9. Rollback plan

                    """).append(design.rollbackPlan()).append("\n\n## 10. Approvals and decisions\n\n");
            List<Approval> granted = run.approvals.values().stream().filter(a -> a.status == Approval.Status.GRANTED).toList();
            if (granted.isEmpty()) md.append("- none yet\n");
            granted.forEach(a -> md.append("- ").append(a.stageId).append(" (").append(a.kind.name().toLowerCase()).append(") approved by **")
                    .append(a.decidedBy).append("** at ").append(a.decidedAt).append(a.choice == null ? "" : ", choice \"" + a.choice + "\"")
                    .append(a.reason == null ? "" : ": " + a.reason).append('\n'));
            md.append("\n| ID | Stage | Actor | Decision | Rationale |\n|---|---|---|---|---|\n");
            run.decisions.forEach(d -> md.append("| ").append(d.id).append(" | ").append(d.stageId).append(" | ").append(d.actor).append(" | ")
                    .append(cell(d.decision)).append(" | ").append(cell(d.rationale)).append(" |\n"));
            md.append("\n**Pending:** release approval by a release manager.\n");
        }
        return md.toString();
    }

    private static String cell(String text) {
        return text == null ? "" : text.replace("|", "/").replace("\n", " ");
    }

    private static void write(Path file, String text) {
        try {
            Files.writeString(file, text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
