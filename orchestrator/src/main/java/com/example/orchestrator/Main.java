package com.example.orchestrator;

import static com.example.orchestrator.core.ConsoleLogger.DIM;
import static com.example.orchestrator.core.ConsoleLogger.GREEN;
import static com.example.orchestrator.core.ConsoleLogger.MAGENTA;
import static com.example.orchestrator.core.ConsoleLogger.RED;
import static com.example.orchestrator.core.ConsoleLogger.RESET;
import static com.example.orchestrator.core.ConsoleLogger.YELLOW;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.orchestrator.artifacts.Artifacts.ChangeSet;
import com.example.orchestrator.artifacts.Artifacts.Design;
import com.example.orchestrator.artifacts.Artifacts.EngineeringSummary;
import com.example.orchestrator.core.AuditLog;
import com.example.orchestrator.core.ConsoleLogger;
import com.example.orchestrator.core.Metrics;
import com.example.orchestrator.core.Orchestrator;
import com.example.orchestrator.core.RunContext;
import com.example.orchestrator.model.Approval;
import com.example.orchestrator.model.Run;
import com.example.orchestrator.model.RunOptions;
import com.example.orchestrator.model.StageState;
import com.example.orchestrator.scenarios.Scenario;
import com.example.orchestrator.scenarios.Scenarios;

/**
 * Command-line interface for the SDLC orchestrator. Run with no arguments for help.
 * Started through the ./orchestrate script from the project root.
 */
public final class Main {

    private static final String HELP = """

            SDLC orchestrator: requirement → design → implementation → tests/security/docs → readiness → release

            Usage: ./orchestrate <command> [options]

              scenarios                                 List available scenarios
              run <scenario> [options]                  Start a new run
                  --llm                                 Use Claude for requirement analysis (needs ANTHROPIC_API_KEY)
                  --release-to project                  Promote to this project on release (default: a sandbox copy)
                  --inject <stage>=<n>                  Failure drill: fail the first n attempts of a stage (repeatable)
                  --fail-release-verify                 Failure drill: make post-release verification fail (tests rollback)
                  --no-rollback                         Keep side effects after a failure, for debugging
              status [runId]                            Show a run (default: latest)
              list                                      List all runs
              approve <runId> <stage> --by <name> [--choice <id>] [--reason "..."]   Approve, then resume
              reject  <runId> <stage> --by <name> --reason "..."                     Reject, then resume (fails + rolls back)
              resume <runId>                            Continue a paused, stopped or failed run
              stop <runId> [--by <name>] [--reason "..."]  Safe-stop a run executing in another terminal
              replan <runId> --from <stage> --by <name> --note "..."   Re-run a stage with reviewer guidance
              audit <runId> [--json]                    Show and verify the hash-chained audit trail
              lineage <runId> <artifactId>              Show where an artifact came from
              graph <runId>                             Print the current dependency graph (Mermaid)
              metrics                                   Reliability metrics across all runs
            """;

    private record Args(List<String> positional, Map<String, List<String>> flags) {
        String flag(String name) {
            List<String> values = flags.get(name);
            return values == null || values.isEmpty() ? null : values.get(values.size() - 1);
        }

        boolean has(String name) {
            return flags.containsKey(name);
        }

        String required(String name) {
            String value = flag(name);
            if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing --" + name + ". Run ./orchestrate for help.");
            return value;
        }

        String position(int index, String name) {
            if (positional.size() <= index) throw new IllegalArgumentException("Missing " + name + ". Run ./orchestrate for help.");
            return positional.get(index);
        }
    }

    public static void main(String[] argv) {
        try {
            System.exit(run(argv));
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println(RED + "Error: " + e.getMessage() + RESET);
            System.exit(1);
        }
    }

    static int run(String[] argv) {
        if (argv.length == 0) {
            System.out.println(HELP);
            return 0;
        }
        Path projectRoot = Path.of("").toAbsolutePath();
        Orchestrator orchestrator = OrchestratorFactory.create(projectRoot, projectRoot.resolve(".orchestrator"), new ConsoleLogger());
        Args args = parse(List.of(argv).subList(1, argv.length));

        switch (argv[0]) {
            case "scenarios" -> {
                for (Scenario s : new Scenarios(projectRoot).list()) {
                    System.out.printf("  %-26s [%s] %s%n", s.id(), s.type(), s.title());
                }
            }
            case "run" -> {
                RunOptions options = new RunOptions();
                options.useLlm = args.has("llm");
                options.releaseTo = "project".equals(args.flag("release-to")) ? "project" : "sandbox";
                options.failReleaseVerification = args.has("fail-release-verify");
                options.rollback = !args.has("no-rollback");
                for (String injection : args.flags().getOrDefault("inject", List.of())) {
                    String[] parts = injection.split("=");
                    options.injectFailures.put(parts[0], parts.length > 1 ? Integer.parseInt(parts[1]) : 1);
                }
                Run run = orchestrator.createRun(args.position(0, "scenario"), options);
                System.out.println("Run " + run.id + "\n");
                printStatus(orchestrator, orchestrator.execute(run.id));
            }
            case "resume" -> printStatus(orchestrator, orchestrator.execute(args.position(0, "runId")));
            case "approve", "reject" -> {
                String runId = args.position(0, "runId");
                boolean granted = argv[0].equals("approve");
                orchestrator.recordApproval(runId, args.position(1, "stage"), args.required("by"), granted,
                        granted ? args.flag("reason") : args.required("reason"), args.flag("choice"));
                printStatus(orchestrator, orchestrator.execute(runId));
            }
            case "replan" -> {
                String runId = args.position(0, "runId");
                orchestrator.requestReplan(runId, args.required("from"), args.required("note"), args.required("by"));
                printStatus(orchestrator, orchestrator.execute(runId));
            }
            case "stop" -> {
                orchestrator.requestStop(args.position(0, "runId"), args.flag("by") != null ? args.flag("by") : System.getProperty("user.name"),
                        args.flag("reason") != null ? args.flag("reason") : "manual safe-stop");
                System.out.println("Safe-stop requested. The executing process stops at its next check and keeps the run resumable.");
            }
            case "status" -> {
                List<Run> runs = orchestrator.store().list();
                Run run = args.positional().isEmpty() ? (runs.isEmpty() ? null : runs.get(runs.size() - 1)) : orchestrator.store().load(args.positional().get(0));
                if (run == null) System.out.println("No runs yet.");
                else printStatus(orchestrator, run);
            }
            case "list" -> orchestrator.store().list().forEach(r -> System.out.printf("  %s  %-18s %s%n", r.id, lower(r.status), r.scenarioId));
            case "audit" -> {
                AuditLog.Verification result = AuditLog.verify(orchestrator.store().auditPath(args.position(0, "runId")));
                if (args.has("json")) {
                    System.out.println(com.example.orchestrator.core.Json.toTree(result.entries()).toPrettyString());
                } else {
                    for (AuditLog.Entry e : result.entries()) {
                        System.out.printf("%4d %s %-24s %-18s %s %s%n", e.seq(), e.ts().substring(11, 19), e.type(),
                                e.stageId() == null ? "" : e.stageId(), e.actor(), e.hash().substring(0, 10));
                    }
                }
                System.out.println(result.valid() ? "\n" + GREEN + "✔ audit chain valid (" + result.entries().size() + " entries)" + RESET
                        : "\n" + RED + "✖ audit chain BROKEN at entry " + result.brokenAt() + ": " + result.reason() + RESET);
                return result.valid() ? 0 : 1;
            }
            case "lineage" -> {
                Run run = orchestrator.store().load(args.position(0, "runId"));
                RunContext.Lineage tree = orchestrator.contextFor(run).lineage(args.position(1, "artifactId"), null);
                if (tree == null) System.out.println("Artifact not found.");
                else printLineage(tree, "", new HashSet<>());
            }
            case "graph" -> {
                Run run = orchestrator.store().load(args.position(0, "runId"));
                System.out.println(orchestrator.graphFor(run).toMermaid(id -> run.stages.get(id).status));
            }
            case "metrics" -> {
                List<Run> runs = orchestrator.store().list();
                Map<String, List<AuditLog.Entry>> audits = new LinkedHashMap<>();
                runs.forEach(r -> audits.put(r.id, AuditLog.readEntries(orchestrator.store().auditPath(r.id))));
                printMetrics(Metrics.compute(runs, audits));
            }
            default -> System.out.println(HELP);
        }
        return 0;
    }

    private static void printStatus(Orchestrator orchestrator, Run run) {
        System.out.println();
        System.out.println("Run " + run.id + "  scenario: " + run.scenarioId + "  status: " + paint(run.status.name()));
        if (run.statusReason != null) System.out.println("  " + run.statusReason);
        System.out.println();

        for (String id : orchestrator.graphFor(run).order()) {
            StageState state = run.stages.get(id);
            Run.PlanEntry entry = run.plan.stream().filter(e -> e.id.equals(id)).findFirst().orElseThrow();
            String attempts = state.attempts.isEmpty() ? "" : state.attempts.size() + " attempt(s)";
            String added = entry.addedBy == null ? "" : " (added by " + entry.addedBy + ")";
            System.out.printf("  %-18s %-30s %s%s%n", id, paint(state.status.name()), attempts, added);
            if (state.error != null && state.status != StageState.Status.COMPLETED) System.out.printf("  %-18s %s%s%s%n", "", DIM, state.error, RESET);
        }

        for (Approval approval : run.approvals.values()) {
            if (approval.status != Approval.Status.PENDING) continue;
            System.out.println("\n" + MAGENTA + "Approval needed: " + approval.stageId + " (" + lower(approval.kind) + "), role: "
                    + String.join(" or ", approval.roles) + RESET);
            approval.reasons.forEach(reason -> System.out.println("  - " + reason));
            if (approval.options != null) {
                System.out.println("  Options:");
                approval.options.forEach(o -> System.out.printf("    %-22s %s%n", o.id(), o.description()));
            }
            String choice = approval.options != null ? " --choice <option>" : "";
            System.out.println("  → ./orchestrate approve " + run.id + " " + approval.stageId + " --by <name>" + choice);
            System.out.println("  → ./orchestrate reject  " + run.id + " " + approval.stageId + " --by <name> --reason \"...\"");
        }

        RunContext ctx = orchestrator.contextFor(run);
        Design design = ctx.get("design", Design.class);
        if (design != null && !design.automatable()) {
            System.out.println("\n" + YELLOW + "Handed off to a human engineer:" + RESET);
            design.handoffTasks().forEach(task -> System.out.println("  - " + task));
        }
        EngineeringSummary summary = ctx.get("engineeringSummary", EngineeringSummary.class);
        if (summary != null) System.out.println("\nEngineering summary: " + summary.markdownFile());
        ChangeSet changes = ctx.get("changeSet", ChangeSet.class);
        if (changes != null) System.out.println("Patch for review:    " + changes.patchFile());
    }

    private static void printLineage(RunContext.Lineage node, String indent, Set<String> seen) {
        String key = node.id() + "@" + node.version();
        if (!seen.add(key)) {
            System.out.println(indent + node.id() + " v" + node.version() + "  (see above)");
            return;
        }
        System.out.println(indent + node.id() + " v" + node.version() + "  ← produced by " + node.producedBy() + " at " + node.createdAt());
        node.decisions().forEach(d -> System.out.println(indent + "    decision " + d));
        node.derivedFrom().forEach(child -> printLineage(child, indent + "  ", seen));
    }

    private static void printMetrics(Metrics.Report m) {
        System.out.printf("Runs:                %d (completed %d, failed %d, in progress %d)%n", m.runs(), m.completed(), m.failed(), m.inProgress());
        System.out.println("Success rate:        " + pct(m.successRate()));
        System.out.println("First-pass yield:    " + pct(m.firstPassYield()) + "  (stage executions that passed on attempt 1)");
        System.out.println("Retry frequency:     " + pct(m.retryFrequency()) + "  (retries / attempts)");
        System.out.println("Rollback frequency:  " + pct(m.rollbackFrequency()) + "  (runs with a rollback)");
        System.out.println("Fallbacks used:      " + m.fallbacksUsed());
        System.out.println("MTTR:                " + sec(m.mttrMs()) + "  (" + m.recovered() + " recovered, " + m.unrecovered() + " unrecovered incidents)");
        System.out.println("E2E latency (avg):   " + sec(m.averageLatencyMs()) + " total, " + sec(m.averageActiveLatencyMs()) + " excluding approval waits");
        System.out.println("Approval wait (avg): " + sec(m.averageApprovalWaitMs()) + " over " + m.approvals() + " approvals");
        System.out.println("Policy findings:     " + m.policyFindings().get("block") + " block, " + m.policyFindings().get("approval") + " approval, "
                + m.policyFindings().get("warn") + " warn");
    }

    private static String paint(String status) {
        String color = switch (status) {
            case "COMPLETED" -> GREEN;
            case "FAILED", "BLOCKED", "REJECTED" -> RED;
            case "AWAITING_APPROVAL" -> MAGENTA;
            case "ROLLED_BACK", "STOPPED" -> YELLOW;
            case "PENDING" -> DIM;
            default -> "";
        };
        return color + status.toLowerCase() + RESET;
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase();
    }

    private static String pct(Double value) {
        return value == null ? "n/a" : String.format("%.1f%%", value * 100);
    }

    private static String sec(Double millis) {
        return millis == null ? "n/a" : String.format("%.1f s", millis / 1000);
    }

    private static Args parse(List<String> args) {
        List<String> positional = new ArrayList<>();
        Map<String, List<String>> flags = new LinkedHashMap<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.startsWith("--")) {
                String name = arg.substring(2);
                boolean hasValue = i + 1 < args.size() && !args.get(i + 1).startsWith("--");
                flags.computeIfAbsent(name, key -> new ArrayList<>()).add(hasValue ? args.get(++i) : "");
            } else {
                positional.add(arg);
            }
        }
        return new Args(positional, flags);
    }
}
