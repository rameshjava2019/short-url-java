package com.example.orchestrator.agents;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import com.example.orchestrator.agents.lib.Codebase;
import com.example.orchestrator.artifacts.Artifacts.Clarification;
import com.example.orchestrator.artifacts.Artifacts.Criterion;
import com.example.orchestrator.artifacts.Artifacts.Design;
import com.example.orchestrator.artifacts.Artifacts.ImpactedFile;
import com.example.orchestrator.artifacts.Artifacts.RelatedFile;
import com.example.orchestrator.artifacts.Artifacts.Spec;
import com.example.orchestrator.artifacts.Artifacts.Task;
import com.example.orchestrator.artifacts.Artifacts.Trace;
import com.example.orchestrator.core.Json;
import com.example.orchestrator.model.PlanChange;
import com.example.orchestrator.policies.WriteGuard;
import com.example.orchestrator.scenarios.ChangePlan;

/**
 * Design + impact analysis (codebase reasoning).
 * <ol>
 *   <li>Picks the reviewed change plan that matches the spec (and the human's
 *       clarification, if there was an ambiguity). No match: not automatable, and a
 *       hand-off task list is produced for a human instead.</li>
 *   <li>Analyses the impact on the real codebase: which files change, which classes
 *       depend on them, which other files relate to the requirement.</li>
 *   <li>Traces every acceptance criterion to at least one task.</li>
 *   <li>Re-plans the workflow when needed: security-sensitive files add a human
 *       security review stage; changes to MongoDB documents add a compatibility check.</li>
 * </ol>
 */
public final class DesignAgent implements Agent {

    @Override
    public String name() {
        return "design";
    }

    @Override
    public AgentResult run(AgentContext context) {
        var ctx = context.ctx();
        Spec spec = ctx.get("spec", Spec.class);
        Clarification clarification = ctx.get("requirements.clarification", Clarification.class);
        List<ChangePlan> plans = ctx.scenario().changePlans();
        ChangePlan plan = plans.stream().filter(p -> p.matches().test(spec, clarification)).findFirst().orElse(null);

        if (plan == null) {
            String reason = clarification != null
                    ? "no reviewed change plan implements the chosen option \"" + clarification.choice() + "\""
                    : "no reviewed change plan matches this requirement";
            List<String> tasks = new ArrayList<>(List.of(
                    "Write a technical design for the chosen option and get it reviewed",
                    "Add a reviewed change plan to the scenario so the pipeline can automate it"));
            spec.acceptanceCriteria().forEach(c -> tasks.add("Implement and test: " + c.text()));
            return AgentResult.of("design", Design.handoff(reason, tasks))
                    .decision("Hand off to a human engineer", reason, plans.stream().map(ChangePlan::title).toList())
                    .summary("not automatable → hand-off");
        }

        List<Criterion> criteria = new ArrayList<>(spec.acceptanceCriteria());
        for (int i = 0; i < plan.refinedCriteria().size(); i++) {
            criteria.add(new Criterion("AC-R" + (i + 1), plan.refinedCriteria().get(i), true, true));
        }

        List<ImpactedFile> impacted = new ArrayList<>();
        for (String file : new LinkedHashSet<>(plan.operations().stream().map(ChangePlan.Operation::file).toList())) {
            boolean creates = plan.operations().stream().anyMatch(op -> op.file().equals(file) && op.type().equals("create"));
            impacted.add(new ImpactedFile(file, creates ? "create" : "modify",
                    creates ? List.of() : Codebase.findDependents(ctx.projectRoot(), file), WriteGuard.isSecuritySensitive(file)));
        }
        List<RelatedFile> related = Codebase.searchKeywords(ctx.projectRoot(), spec.keywords(), 8).stream()
                .filter(r -> impacted.stream().noneMatch(f -> f.file().equals(r.file()))).toList();
        context.log("impact: " + impacted.size() + " files change, " + related.size() + " related files reviewed");

        List<Trace> traceability = criteria.stream().map(c -> new Trace(c.id(), c.text(), plan.tasks().stream()
                .filter(t -> t.keywords().stream().anyMatch(k -> c.text().toLowerCase(Locale.ROOT).contains(k)))
                .map(ChangePlan.Task::id).toList())).toList();

        List<String> sensitive = impacted.stream().filter(ImpactedFile::securitySensitive).map(ImpactedFile::file).toList();
        boolean schemaChange = impacted.stream().anyMatch(f -> f.file().contains("/model/"));

        AgentResult result = new AgentResult();
        if (!sensitive.isEmpty()) {
            result.planChange(PlanChange.addStage("securityReview", "securityReview", List.of("design"), List.of("implementation"),
                    "security-sensitive files: " + String.join(", ", sensitive)));
        }
        if (schemaChange) {
            result.planChange(PlanChange.addStage("dataCompatibility", "dataCompatibility", List.of("implementation"), List.of("readiness"),
                    "MongoDB document changes"));
        }

        int estimatedLines = plan.operations().stream().mapToInt(op -> (op.text() == null ? "" : op.text()).split("\n").length).sum();
        Design design = new Design(true, null, List.of(), plan.id(), Json.hash(plan.operations()), plan.title(), plan.approach(), criteria,
                plan.tasks().stream().map(t -> new Task(t.id(), t.title(), t.dependsOn())).toList(), traceability, impacted, related, sensitive,
                schemaChange, estimatedLines, plan.risks(), plan.assumptions(), plan.docsExpectations(), plan.rollbackPlan());

        result.artifact("design", design)
                .decision("Use change plan \"" + plan.id() + "\": " + plan.title(), plan.approach(), plan.alternativesConsidered())
                .summary(impacted.size() + " files, " + plan.tasks().size() + " tasks, " + result.planChanges().size() + " plan change(s)");
        if (!plan.refinedCriteria().isEmpty() && clarification != null) {
            result.decision("Refined acceptance criteria for \"" + clarification.choice() + "\"", String.join(" | ", plan.refinedCriteria()));
        }
        return result;
    }
}
