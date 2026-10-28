package com.example.orchestrator.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

import com.example.orchestrator.agents.Agent;
import com.example.orchestrator.agents.AgentContext;
import com.example.orchestrator.agents.AgentResult;
import com.example.orchestrator.model.Approval;
import com.example.orchestrator.model.Decision;
import com.example.orchestrator.model.PlanChange;
import com.example.orchestrator.model.PolicyResult;
import com.example.orchestrator.model.Run;
import com.example.orchestrator.model.Run.Halt;
import com.example.orchestrator.model.Run.PlanEntry;
import com.example.orchestrator.model.Run.RunStatus;
import com.example.orchestrator.model.RunOptions;
import com.example.orchestrator.model.StageState;
import com.example.orchestrator.model.StageState.Attempt;
import com.example.orchestrator.model.StageState.Status;
import com.example.orchestrator.scenarios.Scenario;
import com.example.orchestrator.workflow.Stage;
import com.example.orchestrator.workflow.StageTemplate;
import com.example.orchestrator.workflow.StageTemplate.ApprovalRequirement;
import com.example.orchestrator.workflow.Workflow;

/**
 * The orchestration engine.
 *
 * It executes a workflow (a dependency graph of stages) for one run:
 * <ul>
 *   <li>schedules every stage whose upstream stages are complete, in parallel</li>
 *   <li>enforces entry gates, exit gates and policy guardrails around each stage</li>
 *   <li>retries transient failures with exponential backoff, then tries a fallback agent</li>
 *   <li>pauses for human approval at high-impact checkpoints</li>
 *   <li>rolls back side effects (compensation) when the run fails</li>
 *   <li>honours safe-stop requests at any time</li>
 *   <li>re-plans: when an upstream artifact changes, only affected stages re-run;
 *       agents may also add stages to the plan, within policy limits</li>
 *   <li>writes every event to a hash-chained audit trail</li>
 * </ul>
 *
 * Stages run on worker threads. Every change to the run state happens inside
 * {@code synchronized (run)}; agents themselves run outside the lock.
 */
public final class Orchestrator {

    /** Tunable engine behaviour. */
    public record Settings(int concurrency, long backoffMs, long maxBackoffMs, int maxTotalRetries, long pollMs,
            Map<String, List<String>> approvers) {

        public static Settings defaults(Map<String, List<String>> approvers) {
            return new Settings(3, 250, 4000, 10, 100, approvers);
        }
    }

    // Stages in these states start again when a failed / stopped run is resumed.
    private static final List<Status> RETRYABLE_ON_RESUME = List.of(Status.FAILED, Status.BLOCKED, Status.REJECTED, Status.ROLLED_BACK);

    private final RunStore store;
    private final Workflow workflow;
    private final Map<String, Agent> agents;
    private final PolicyEngine policyEngine;
    private final Function<String, Scenario> scenarios;
    private final java.nio.file.Path projectRoot;
    private final Consumer<AuditLog.Entry> logger;
    private final Settings settings;
    private final Map<String, AuditLog> auditLogs = new ConcurrentHashMap<>();
    private final ExecutorService agentPool = Executors.newCachedThreadPool(daemonThreads("agent"));

    public Orchestrator(RunStore store, Workflow workflow, Map<String, Agent> agents, PolicyEngine policyEngine,
            Function<String, Scenario> scenarios, java.nio.file.Path projectRoot, Consumer<AuditLog.Entry> logger, Settings settings) {
        this.store = store;
        this.workflow = workflow;
        this.agents = agents;
        this.policyEngine = policyEngine;
        this.scenarios = scenarios;
        this.projectRoot = projectRoot;
        this.logger = logger;
        this.settings = settings;
    }

    public RunStore store() {
        return store;
    }

    public Map<String, Agent> agents() {
        return agents;
    }

    // =====================================================================
    // Public API
    // =====================================================================

    public Run createRun(String scenarioId, RunOptions options) {
        Scenario scenario = scenarios.apply(scenarioId);
        Run run = new Run();
        run.id = store.newRunId();
        run.scenarioId = scenario.id();
        run.scenarioTitle = scenario.title();
        run.createdAt = now();
        run.options = options;
        for (PlanEntry entry : workflow.initialPlan()) {
            run.plan.add(new PlanEntry(entry.id, entry.template, entry.dependsOn, null));
            run.stages.put(entry.id, new StageState());
        }
        new DependencyGraph(run.plan); // validate before persisting anything

        store.create(run);
        audit(run, "run.created", Map.of("scenario", scenario.id()), null, null, "engine");
        refreshExternalInputs(run, contextFor(run));
        store.save(run);
        return run;
    }

    /** Runs (or resumes) a run until it completes, fails, stops or needs a human. */
    public Run execute(String runId) {
        Run run = store.load(runId);
        store.acquireLock(runId);
        store.clearStop(runId); // resuming means the human wants to continue
        auditLogs.remove(runId); // another process may have appended since we last wrote

        try {
            AuditLog.Verification integrity = AuditLog.verify(store.auditPath(runId));
            if (!integrity.valid()) {
                // Never continue on top of a tampered audit trail.
                run.status = RunStatus.STOPPED;
                run.statusReason = "Safe-stop: audit trail failed verification at entry " + integrity.brokenAt() + " (" + integrity.reason() + ")";
                return run;
            }

            RunContext ctx = contextFor(run);
            synchronized (run) {
                boolean resume = run.startedAt != null;
                run.halt = null;
                if (run.startedAt == null) run.startedAt = now();
                run.status = RunStatus.RUNNING;
                run.statusReason = null;
                audit(run, resume ? "run.resumed" : "run.started", Map.of(), null, null, "engine");

                if (resume) resetRetryableStages(run);
                refreshExternalInputs(run, ctx);
                processApprovalDecisions(run, ctx);
                invalidateStaleStages(run, ctx);
                store.save(run);
            }

            RunStore.StopRequest stop = schedule(run, ctx);
            synchronized (run) {
                finalizeRun(run, ctx, stop);
            }
        } catch (RuntimeException e) {
            synchronized (run) {
                run.status = RunStatus.STOPPED;
                run.statusReason = "Safe-stop after engine error: " + e.getMessage();
                audit(run, "run.stopped", Map.of("reason", run.statusReason), null, null, "engine");
            }
        } finally {
            store.save(run);
            store.releaseLock(runId);
        }
        return run;
    }

    /** Records a human approval or rejection. Takes effect on the next {@link #execute}. */
    public Approval recordApproval(String runId, String stageId, String approver, boolean granted, String reason, String choice) {
        return withRun(runId, (run, ctx) -> {
            Approval request = run.approvals.values().stream()
                    .filter(a -> a.stageId.equals(stageId) && a.status == Approval.Status.PENDING)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No pending approval for stage \"" + stageId + "\""));

            List<String> roles = settings.approvers().getOrDefault(approver, List.of());
            if (request.roles.stream().noneMatch(roles::contains)) {
                throw new IllegalArgumentException("\"" + approver + "\" cannot decide this approval. Required role: "
                        + String.join(" or ", request.roles) + ". Approvers and roles are configured in orchestrator/approvers.json.");
            }
            if (granted && request.options != null && !request.options.isEmpty()
                    && request.options.stream().noneMatch(o -> o.id().equals(choice))) {
                throw new IllegalArgumentException("This approval needs --choice, one of: "
                        + String.join(", ", request.options.stream().map(Approval.Option::id).toList()));
            }

            request.status = granted ? Approval.Status.GRANTED : Approval.Status.REJECTED;
            request.decidedBy = approver;
            request.decidedAt = now();
            request.reason = reason;
            request.choice = choice;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("key", request.key);
            data.put("reason", reason);
            data.put("choice", choice);
            audit(run, granted ? "approval.granted" : "approval.rejected", data, stageId, null, "human:" + approver);
            ctx.recordDecision(stageId, "human:" + approver,
                    (granted ? "Approved " : "Rejected ") + request.kind.name().toLowerCase() + " checkpoint" + (choice != null ? " (choice: " + choice + ")" : ""),
                    reason == null ? "(no reason given)" : reason, List.of(), List.of());
            return request;
        });
    }

    /** Human-triggered re-plan: re-run {@code fromStage} (and whatever its new output affects). */
    public void requestReplan(String runId, String fromStage, String note, String by) {
        withRun(runId, (run, ctx) -> {
            if (!run.stages.containsKey(fromStage)) throw new IllegalArgumentException("Unknown stage \"" + fromStage + "\"");
            Run.Note entry = new Run.Note();
            entry.note = note;
            entry.by = by;
            entry.at = now();
            run.notes.put(fromStage, entry);
            audit(run, "replan.requested", Map.of("fromStage", fromStage, "note", note), fromStage, null, "human:" + by);
            ctx.recordDecision(fromStage, "human:" + by, "Re-plan from \"" + fromStage + "\"", note, List.of(), List.of());
            return null;
        });
    }

    /**
     * Asks the process executing the run to stop safely. It sees the request within
     * one poll interval, aborts in-flight agents, records the stop and leaves the run resumable.
     */
    public void requestStop(String runId, String by, String reason) {
        store.requestStop(runId, by, reason);
    }

    public DependencyGraph graphFor(Run run) {
        synchronized (run) {
            return new DependencyGraph(run.plan);
        }
    }

    public RunContext contextFor(Run run) {
        return new RunContext(run, store, projectRoot, scenarios.apply(run.scenarioId));
    }

    // =====================================================================
    // Scheduling
    // =====================================================================

    /**
     * Starts every ready stage (up to the concurrency limit) and keeps going as stages
     * finish. Returns the stop request if a safe-stop was triggered.
     */
    private RunStore.StopRequest schedule(Run run, RunContext ctx) {
        StopSignal runSignal = new StopSignal();
        Map<String, Future<?>> running = new LinkedHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(settings.concurrency(), daemonThreads("stage"));
        RunStore.StopRequest stop = null;

        try {
            while (true) {
                running.values().removeIf(Future::isDone);

                if (stop == null) {
                    stop = store.readStop(run.id);
                    if (stop != null) {
                        synchronized (run) {
                            audit(run, "run.stop_requested", Map.of("by", stop.by(), "reason", stop.reason()), null, null, "human:" + stop.by());
                        }
                        runSignal.stop(); // in-flight agents get the signal and stop
                    }
                }

                synchronized (run) {
                    if (run.halt == null && stop == null) {
                        for (String stageId : graphFor(run).readyStages(id -> run.stages.get(id).status)) {
                            if (running.size() >= settings.concurrency()) break;
                            if (running.containsKey(stageId)) continue;
                            running.put(stageId, pool.submit(() -> runStageSafely(run, ctx, stageId, runSignal)));
                        }
                    }
                }

                if (running.isEmpty()) break;
                sleep(settings.pollMs());
            }
        } finally {
            pool.shutdownNow();
        }
        return stop;
    }

    private void runStageSafely(Run run, RunContext ctx, String stageId, StopSignal runSignal) {
        try {
            runStage(run, ctx, stageId, runSignal);
        } catch (RuntimeException e) {
            synchronized (run) {
                if (run.halt == null) run.halt = new Halt(stageId, "Engine error in \"" + stageId + "\": " + e.getMessage());
            }
        }
    }

    private void finalizeRun(Run run, RunContext ctx, RunStore.StopRequest stop) {
        if (stop != null) {
            run.status = RunStatus.STOPPED;
            run.statusReason = "Safe-stop requested by " + stop.by() + ": " + stop.reason();
            audit(run, "run.stopped", Map.of("reason", run.statusReason), null, null, "engine");
            return;
        }

        if (run.halt != null) {
            if (run.options.rollback) rollbackCompletedStages(run, ctx, run.halt.reason);
            run.status = RunStatus.FAILED;
            run.statusReason = "Stage \"" + run.halt.stageId + "\": " + run.halt.reason;
            run.finishedAt = now();
            audit(run, "run.failed", Map.of("reason", run.statusReason), null, null, "engine");
            return;
        }

        List<Status> statuses = run.plan.stream().map(e -> run.stages.get(e.id).status).toList();
        if (statuses.stream().allMatch(s -> s == Status.COMPLETED)) {
            run.status = RunStatus.COMPLETED;
            run.finishedAt = now();
            audit(run, "run.completed", Map.of("durationMs", millisBetween(run.startedAt, run.finishedAt)), null, null, "engine");
            return;
        }

        if (statuses.contains(Status.AWAITING_APPROVAL)) {
            List<Approval> pending = run.approvals.values().stream().filter(a -> a.status == Approval.Status.PENDING).toList();
            run.status = RunStatus.AWAITING_APPROVAL;
            run.statusReason = "Waiting for: " + String.join(", ", pending.stream().map(a -> a.stageId + " (" + String.join("/", a.roles) + ")").toList());
            audit(run, "run.paused", Map.of("pendingApprovals", pending.stream().map(a -> a.key).toList()), null, null, "engine");
            return;
        }

        run.status = RunStatus.STOPPED;
        run.statusReason = "Safe-stop: no stage can make progress";
        audit(run, "run.stopped", Map.of("reason", run.statusReason), null, null, "engine");
    }

    // =====================================================================
    // Stage execution
    // =====================================================================

    private void runStage(Run run, RunContext ctx, String stageId, StopSignal runSignal) {
        Stage stage;
        StageState state;
        synchronized (run) {
            stage = stageDefinition(run, stageId);
            state = run.stages.get(stageId);
            String fingerprint = inputFingerprint(run, ctx, stage);
            StageTemplate template = stage.template();

            // ---- Entry gate: preconditions that must hold before the stage may start
            GateResult entry = evaluateGates(template.entryGates().stream()
                    .map(g -> new GateCheck(g.id(), true, () -> g.check().apply(ctx))).toList());
            audit(run, entry.passed ? "gate.entry.passed" : "gate.entry.failed", Map.of("results", entry.results), stageId, null, "engine");
            if (!entry.passed) {
                state.status = Status.BLOCKED;
                state.error = "Entry gate failed: " + entry.reason;
                run.halt = new Halt(stageId, state.error);
                store.save(run);
                return;
            }

            // ---- Human checkpoint before a high-impact action
            if (template.approvalBefore() != null) {
                ApprovalRequirement requirement = template.approvalBefore().apply(ctx);
                if (requirement != null && !hasGrantedApproval(run, stageId, Approval.Kind.BEFORE, fingerprint)) {
                    state.inputFingerprint = fingerprint;
                    requestApproval(run, stageId, Approval.Kind.BEFORE, fingerprint, List.of(requirement.role()), List.of(requirement.reason()), null);
                    state.status = Status.AWAITING_APPROVAL;
                    store.save(run);
                    return;
                }
            }

            state.status = Status.RUNNING;
            state.inputFingerprint = fingerprint;
            state.startedAt = now();
            state.error = null;
            audit(run, "stage.started", Map.of("fingerprint", fingerprint), stageId, null, "engine");
            store.save(run);
        }

        Outcome outcome = executeWithRetries(run, ctx, stage, runSignal);

        synchronized (run) {
            if (outcome.aborted) {
                state.status = Status.PENDING; // runs again on resume
                audit(run, "stage.aborted", Map.of(), stageId, null, "engine");
                store.save(run);
                return;
            }
            if (!outcome.ok) {
                state.status = outcome.blocked ? Status.BLOCKED : Status.FAILED;
                state.error = outcome.error;
                state.failedAt = now();
                run.halt = new Halt(stageId, outcome.error);
                audit(run, outcome.blocked ? "stage.blocked" : "stage.failed", Map.of("error", outcome.error), stageId, null, "engine");
                store.save(run);
                return;
            }

            try {
                storeOutputs(run, ctx, stage, outcome.result, outcome.agentName);
            } catch (OrchestratorException e) {
                state.status = Status.FAILED;
                state.error = e.getMessage();
                run.halt = new Halt(stageId, e.getMessage());
                store.save(run);
                return;
            }

            // ---- Policy guardrails on the stage's outputs
            List<PolicyResult> results = policyEngine.evaluate(stageId, ctx);
            run.policyResults.put(stageId, results);
            for (PolicyResult result : results) {
                audit(run, "policy.evaluated", Json.convert(result, Map.class), stageId, null, "engine");
            }
            PolicyEngine.Summary summary = PolicyEngine.summarize(results);

            if (!summary.blocked().isEmpty()) {
                String reason = String.join("; ", summary.blocked().stream().map(r -> "[" + r.ruleId() + "] " + r.message()).toList());
                state.status = Status.BLOCKED;
                state.error = reason;
                run.halt = new Halt(stageId, "Blocked by policy: " + reason);
                audit(run, "stage.blocked", Map.of("reason", reason), stageId, null, "engine");
                store.save(run);
                return;
            }

            state.pendingPlanChanges = new ArrayList<>(outcome.result.planChanges());
            state.outputFingerprint = outputFingerprint(ctx, stage);

            if (!summary.approvals().isEmpty() && !hasGrantedApproval(run, stageId, Approval.Kind.EXIT, state.outputFingerprint)) {
                List<String> roles = new ArrayList<>(new LinkedHashSet<>(summary.approvals().stream().map(PolicyResult::approverRole).toList()));
                List<String> reasons = summary.approvals().stream().map(r -> "[" + r.ruleId() + "] " + r.message()).toList();
                requestApproval(run, stageId, Approval.Kind.EXIT, state.outputFingerprint, roles, reasons, outcome.result.approvalOptions());
                state.status = Status.AWAITING_APPROVAL;
                store.save(run);
                return;
            }

            completeStage(run, ctx, stageId);
        }
    }

    private record Outcome(boolean ok, boolean aborted, boolean blocked, String error, AgentResult result, String agentName) {
        static Outcome success(AgentResult result, String agentName) {
            return new Outcome(true, false, false, null, result, agentName);
        }

        static Outcome failure(String error, boolean blocked) {
            return new Outcome(false, false, blocked, error, null, null);
        }

        static Outcome abort() {
            return new Outcome(false, true, false, null, null, null);
        }
    }

    private record Step(String agentName, int maxAttempts, boolean fallback) {
    }

    /** Primary agent with bounded retries, then (optionally) one fallback attempt. */
    private Outcome executeWithRetries(Run run, RunContext ctx, Stage stage, StopSignal runSignal) {
        StageTemplate template = stage.template();
        StageState state;
        List<Step> steps = new ArrayList<>();
        synchronized (run) {
            state = run.stages.get(stage.id());
            steps.add(new Step(template.agent().apply(run.options), template.maxAttempts(), false));
        }
        if (template.fallbackAgent() != null && !template.fallbackAgent().equals(steps.get(0).agentName())) {
            steps.add(new Step(template.fallbackAgent(), 1, true));
        }

        String lastError = "unknown error";
        for (Step step : steps) {
            if (step.fallback()) {
                synchronized (run) {
                    audit(run, "fallback.used", Map.of("from", steps.get(0).agentName(), "to", step.agentName(), "reason", lastError),
                            stage.id(), null, "engine");
                }
            }
            Agent agent = agents.get(step.agentName());
            if (agent == null) return Outcome.failure("No agent registered as \"" + step.agentName() + "\"", false);

            for (int attempt = 1; attempt <= step.maxAttempts(); attempt++) {
                if (runSignal.isStopped()) return Outcome.abort();

                Attempt record = new Attempt();
                synchronized (run) {
                    record.number = state.attempts.size() + 1;
                    record.agent = step.agentName();
                    record.fallback = step.fallback();
                    record.startedAt = now();
                    state.attempts.add(record);
                    audit(run, "attempt.started", Map.of("agent", step.agentName(), "fallback", step.fallback()), stage.id(), record.number, "engine");
                }

                try {
                    maybeInjectFailure(run, stage.id(), step, record);
                    AgentResult result = runAgentWithTimeout(agent, run, ctx, stage, runSignal);

                    // ---- Exit gate: the stage's own definition of done
                    GateResult exit = evaluateGates(template.exitGates().stream()
                            .map(g -> new GateCheck(g.id(), g.retryable(), () -> g.check().apply(result, ctx))).toList());
                    synchronized (run) {
                        audit(run, exit.passed ? "gate.exit.passed" : "gate.exit.failed", Map.of("results", exit.results), stage.id(), record.number, "engine");
                    }
                    if (!exit.passed) throw new OrchestratorException.GateFailed("Exit gate failed: " + exit.reason, exit.retryable);

                    synchronized (run) {
                        record.ok = true;
                        record.endedAt = now();
                        audit(run, "attempt.succeeded", Map.of("summary", result.summary()), stage.id(), record.number, "engine");
                    }
                    return Outcome.success(result, step.agentName());
                } catch (Exception e) {
                    OrchestratorException error = asOrchestratorException(e);
                    if (runSignal.isStopped() || error instanceof OrchestratorException.Aborted) {
                        synchronized (run) {
                            record.ok = false;
                            record.error = "aborted";
                            record.endedAt = now();
                        }
                        return Outcome.abort();
                    }

                    lastError = error.getMessage();
                    synchronized (run) {
                        record.ok = false;
                        record.error = error.getMessage();
                        record.endedAt = now();
                        audit(run, "attempt.failed", Map.of("error", error.getMessage(), "retryable", error.isRetryable()), stage.id(), record.number, "engine");
                    }

                    if (error instanceof OrchestratorException.PolicyViolation) return Outcome.failure(error.getMessage(), true);
                    if (!error.isRetryable()) break; // pointless to retry; try the fallback

                    if (attempt < step.maxAttempts()) {
                        long delay;
                        synchronized (run) {
                            run.retries++;
                            if (run.retries > settings.maxTotalRetries()) {
                                return Outcome.failure("Retry budget exhausted (" + settings.maxTotalRetries() + "); last error: " + error.getMessage(), false);
                            }
                            delay = Math.min(settings.backoffMs() * (1L << (attempt - 1)), settings.maxBackoffMs());
                            audit(run, "retry.scheduled", Map.of("delayMs", delay, "nextAttempt", attempt + 1), stage.id(), null, "engine");
                        }
                        sleep(delay);
                    }
                }
            }
        }
        return Outcome.failure(lastError, false);
    }

    /** Runs the agent on its own thread so the engine can enforce the timeout and safe-stop. */
    private AgentResult runAgentWithTimeout(Agent agent, Run run, RunContext ctx, Stage stage, StopSignal runSignal) throws Exception {
        StopSignal stageSignal = runSignal.child();
        AgentContext context = new AgentContext(ctx, stage, stageSignal, (message, data) -> {
            Map<String, Object> details = new LinkedHashMap<>(data);
            details.put("message", message);
            synchronized (run) {
                audit(run, "agent.log", details, stage.id(), null, "agent:" + agent.name());
            }
        });

        Future<AgentResult> future = agentPool.submit(() -> agent.run(context));
        long deadline = System.currentTimeMillis() + stage.template().timeout().toMillis();
        try {
            while (true) {
                try {
                    return future.get(settings.pollMs(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException notYet) {
                    if (runSignal.isStopped()) {
                        stageSignal.stop();
                        future.cancel(true);
                        throw new OrchestratorException.Aborted();
                    }
                    if (System.currentTimeMillis() > deadline) {
                        stageSignal.stop();
                        future.cancel(true);
                        throw new OrchestratorException.StageTimeout(stage.id(), stage.template().timeout().toMillis());
                    }
                }
            }
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }

    private void storeOutputs(Run run, RunContext ctx, Stage stage, AgentResult result, String agentName) {
        for (Map.Entry<String, Object> artifact : result.artifacts().entrySet()) {
            if (!stage.template().produces().contains(artifact.getKey())) {
                throw new OrchestratorException.NonRetryable("Stage \"" + stage.id() + "\" produced undeclared artifact \"" + artifact.getKey() + "\"");
            }
            RunContext.PutResult put = ctx.put(artifact.getKey(), artifact.getValue(), stage.id(), stage.template().consumes());
            audit(run, "artifact.recorded", Map.of("artifactId", artifact.getKey(), "version", put.record().version, "hash", put.record().hash,
                    "changed", put.changed()), stage.id(), null, "engine");
        }
        for (AgentResult.DecisionInput decision : result.decisions()) {
            Decision entry = ctx.recordDecision(stage.id(), "agent:" + agentName, decision.decision(), decision.rationale(),
                    decision.alternatives(), stage.template().consumes());
            audit(run, "decision.recorded", Map.of("id", entry.id, "decision", entry.decision), stage.id(), null, "agent:" + agentName);
        }
    }

    private void completeStage(Run run, RunContext ctx, String stageId) {
        StageState state = run.stages.get(stageId);
        try {
            applyPlanChanges(run, ctx, stageId, state.pendingPlanChanges);
        } catch (RuntimeException e) {
            state.status = Status.BLOCKED;
            state.error = e.getMessage();
            run.halt = new Halt(stageId, "Plan change rejected: " + e.getMessage());
            audit(run, "stage.blocked", Map.of("reason", e.getMessage()), stageId, null, "engine");
            store.save(run);
            return;
        }
        state.status = Status.COMPLETED;
        state.completedAt = now();
        state.pendingPlanChanges = new ArrayList<>();
        audit(run, "stage.completed", Map.of("durationMs", millisBetween(state.startedAt, state.completedAt)), stageId, null, "engine");

        // New outputs may make already-completed downstream stages stale.
        invalidateStaleStages(run, ctx);
        store.save(run);
    }

    // =====================================================================
    // Approvals (human-in-the-loop)
    // =====================================================================

    private void requestApproval(Run run, String stageId, Approval.Kind kind, String fingerprint, List<String> roles, List<String> reasons,
            List<Approval.Option> options) {
        Approval approval = new Approval();
        approval.key = stageId + ":" + kind.name().toLowerCase();
        approval.stageId = stageId;
        approval.kind = kind;
        approval.roles = roles;
        approval.reasons = reasons;
        approval.options = options;
        approval.fingerprint = fingerprint;
        approval.requestedAt = now();
        run.approvals.put(approval.key, approval);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", approval.key);
        data.put("roles", roles);
        data.put("reasons", reasons);
        data.put("options", options);
        audit(run, "approval.requested", data, stageId, null, "engine");
    }

    /** An approval only counts if it was given for exactly the current inputs (before) or outputs (exit). */
    private boolean hasGrantedApproval(Run run, String stageId, Approval.Kind kind, String fingerprint) {
        Approval approval = run.approvals.get(stageId + ":" + kind.name().toLowerCase());
        return approval != null && approval.status == Approval.Status.GRANTED && fingerprint.equals(approval.fingerprint);
    }

    private void processApprovalDecisions(Run run, RunContext ctx) {
        List<String> exitApproved = new ArrayList<>();

        for (Approval approval : run.approvals.values()) {
            StageState state = run.stages.get(approval.stageId);
            if (state == null || state.status != Status.AWAITING_APPROVAL || approval.status == Approval.Status.PENDING || approval.processed) continue;
            approval.processed = true;

            if (approval.status == Approval.Status.REJECTED) {
                state.status = Status.REJECTED;
                state.error = "Rejected by " + approval.decidedBy + ": " + (approval.reason == null ? "no reason" : approval.reason);
                run.halt = new Halt(approval.stageId, state.error);
            } else if (approval.kind == Approval.Kind.BEFORE) {
                state.status = Status.PENDING; // runStage will now find the granted approval
            } else {
                if (approval.choice != null) {
                    // A clarification chosen by a human becomes an artifact downstream stages consume.
                    Map<String, Object> clarification = new LinkedHashMap<>();
                    clarification.put("choice", approval.choice);
                    clarification.put("decidedBy", approval.decidedBy);
                    clarification.put("reason", approval.reason);
                    ctx.put(approval.stageId + ".clarification", clarification, "human:" + approval.decidedBy, List.of());
                }
                exitApproved.add(approval.stageId);
            }
        }

        for (String id : graphFor(run).order()) {
            if (exitApproved.contains(id)) completeStage(run, ctx, id);
        }
    }

    // =====================================================================
    // Re-planning
    // =====================================================================

    /**
     * External inputs (the requirement document) are re-read on every execute. If one
     * changed, it gets a new artifact version, and the fingerprint check re-plans the
     * affected stages.
     */
    private void refreshExternalInputs(Run run, RunContext ctx) {
        Scenario scenario = scenarios.apply(run.scenarioId);
        String text = RunStore.uncheck(() -> java.nio.file.Files.readString(scenario.requirementFile()));
        RunContext.PutResult put = ctx.put("requirementSource", Map.of("text", text), "external", List.of());
        if (put.changed()) {
            audit(run, put.record().version == 1 ? "input.registered" : "replan.upstream_changed",
                    Map.of("artifactId", "requirementSource", "version", put.record().version, "hash", put.record().hash), null, null, "engine");
        }
    }

    /**
     * A stage is stale when its current input fingerprint differs from the one it ran
     * with. Stale stages are rolled back (if they had side effects) and re-queued.
     * Stages whose inputs did not change are left alone.
     */
    private void invalidateStaleStages(Run run, RunContext ctx) {
        for (String id : graphFor(run).order()) {
            StageState state = run.stages.get(id);
            if ((state.status != Status.COMPLETED && state.status != Status.AWAITING_APPROVAL) || state.inputFingerprint == null) continue;
            if (!inputFingerprint(run, ctx, stageDefinition(run, id)).equals(state.inputFingerprint)) {
                resetStage(run, ctx, id, "inputs changed since the stage last ran");
            }
        }
    }

    private void resetStage(Run run, RunContext ctx, String stageId, String reason) {
        StageState state = run.stages.get(stageId);
        if (state.status == Status.COMPLETED && stageDefinition(run, stageId).template().sideEffects()) {
            rollbackStage(run, ctx, stageId, "re-plan: " + reason);
        }
        // Approvals are kept: each is bound to a fingerprint, so it only counts again if
        // the re-run sees exactly the same inputs (before) or produces the same outputs (exit).
        state.status = Status.PENDING;
        state.error = null;
        state.inputFingerprint = null;
        audit(run, "replan.stage_reset", Map.of("reason", reason), stageId, null, "engine");
    }

    private void resetRetryableStages(Run run) {
        run.stages.forEach((stageId, state) -> {
            if (RETRYABLE_ON_RESUME.contains(state.status)) {
                audit(run, "replan.stage_reset", Map.of("reason", "resume after " + state.status.name().toLowerCase()), stageId, null, "engine");
                state.status = Status.PENDING;
                state.error = null;
            }
        });
    }

    /**
     * Agents may propose new stages (e.g. "schema changed: add a data compatibility
     * check"). Only templates the workflow marks as agent-addable are accepted, and the
     * new graph must stay acyclic. Stages this stage added earlier but no longer asks
     * for are removed.
     */
    private void applyPlanChanges(Run run, RunContext ctx, String stageId, List<PlanChange> changes) {
        for (PlanChange change : changes) {
            if (!workflow.agentAddableTemplates().contains(change.template)) {
                throw new OrchestratorException.PolicyViolation("CHG-007", "Agents may not add stages of type \"" + change.template + "\"");
            }
        }

        List<PlanEntry> obsolete = run.plan.stream()
                .filter(e -> stageId.equals(e.addedBy) && changes.stream().noneMatch(c -> c.id.equals(e.id)))
                .toList();
        for (PlanEntry entry : obsolete) {
            run.plan.remove(entry);
            run.plan.forEach(e -> e.dependsOn.remove(entry.id));
            run.stages.remove(entry.id);
            audit(run, "plan.stage_removed", Map.of("removed", entry.id), stageId, null, "engine");
        }

        for (PlanChange change : changes) {
            if (run.plan.stream().anyMatch(e -> e.id.equals(change.id))) continue; // already in the plan

            List<PlanEntry> newPlan = new ArrayList<>();
            for (PlanEntry e : run.plan) {
                PlanEntry copy = new PlanEntry(e.id, e.template, e.dependsOn, e.addedBy);
                if (change.before.contains(e.id)) copy.dependsOn.add(change.id);
                newPlan.add(copy);
            }
            newPlan.add(new PlanEntry(change.id, change.template, change.dependsOn, stageId));
            new DependencyGraph(newPlan); // throws on cycles / unknown stages

            run.plan = newPlan;
            run.stages.put(change.id, new StageState());
            audit(run, "plan.stage_added", Map.of("added", change.id, "template", change.template, "dependsOn", change.dependsOn,
                    "before", change.before, "reason", String.valueOf(change.reason)), stageId, null, "engine");

            for (String id : change.before) {
                if (run.stages.get(id).status == Status.COMPLETED) resetStage(run, ctx, id, "new prerequisite stage \"" + change.id + "\"");
            }
        }
    }

    // =====================================================================
    // Rollback (compensation)
    // =====================================================================

    private void rollbackCompletedStages(Run run, RunContext ctx, String reason) {
        List<String> toRollBack = run.plan.stream()
                .map(e -> e.id)
                .filter(id -> run.stages.get(id).status == Status.COMPLETED && stageDefinition(run, id).template().sideEffects())
                .sorted(Comparator.comparing((String id) -> run.stages.get(id).completedAt).reversed()) // newest first
                .toList();
        for (String stageId : toRollBack) rollbackStage(run, ctx, stageId, reason);
    }

    private void rollbackStage(Run run, RunContext ctx, String stageId, String reason) {
        Stage stage = stageDefinition(run, stageId);
        Agent agent = agents.get(stage.template().agent().apply(run.options));
        audit(run, "rollback.started", Map.of("reason", reason), stageId, null, "engine");
        try {
            if (agent != null) {
                agent.rollback(new AgentContext(ctx, stage, new StopSignal(),
                        (message, data) -> audit(run, "agent.log", Map.of("message", message), stageId, null, "agent:" + agent.name())));
            }
            run.stages.get(stageId).status = Status.ROLLED_BACK;
            audit(run, "rollback.completed", Map.of(), stageId, null, "engine");
        } catch (Exception e) {
            run.stages.get(stageId).status = Status.FAILED;
            run.stages.get(stageId).error = "Rollback failed: " + e.getMessage() + ". Manual intervention required.";
            audit(run, "rollback.failed", Map.of("error", String.valueOf(e.getMessage())), stageId, null, "engine");
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    public Stage stageDefinition(Run run, String stageId) {
        PlanEntry entry = run.plan.stream().filter(e -> e.id.equals(stageId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown stage \"" + stageId + "\""));
        StageTemplate template = workflow.templates().get(entry.template);
        if (template == null) throw new IllegalStateException("Unknown stage template \"" + entry.template + "\"");
        return new Stage(entry.id, entry.template, entry.dependsOn, template);
    }

    /** Identifies exactly what a stage ran on: consumed artifact versions + human notes. */
    private String inputFingerprint(Run run, RunContext ctx, Stage stage) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("stage", stage.id());
        inputs.put("template", stage.templateName());
        inputs.put("inputs", stage.template().consumes().stream().map(id -> List.of(id, String.valueOf(ctx.hashOf(id)))).toList());
        Run.Note note = run.notes.get(stage.id());
        inputs.put("note", note == null ? null : note.note);
        return Json.hash(inputs);
    }

    private String outputFingerprint(RunContext ctx, Stage stage) {
        return Json.hash(stage.template().produces().stream().map(id -> List.of(id, String.valueOf(ctx.hashOf(id)))).toList());
    }

    private void maybeInjectFailure(Run run, String stageId, Step step, Attempt record) {
        int wanted = run.options.injectFailures.getOrDefault(stageId, 0);
        if (step.fallback() || wanted == 0) return;
        synchronized (run) {
            long injectedSoFar = run.stages.get(stageId).attempts.stream().filter(a -> a.injected).count();
            if (injectedSoFar < wanted) {
                record.injected = true;
                throw new OrchestratorException("Injected transient failure " + (injectedSoFar + 1) + "/" + wanted + " (failure drill)");
            }
        }
    }

    @FunctionalInterface
    private interface RunAction<T> {
        T apply(Run run, RunContext ctx);
    }

    private <T> T withRun(String runId, RunAction<T> action) {
        store.acquireLock(runId);
        auditLogs.remove(runId);
        try {
            Run run = store.load(runId);
            T result;
            synchronized (run) {
                result = action.apply(run, contextFor(run));
            }
            store.save(run);
            return result;
        } finally {
            store.releaseLock(runId);
        }
    }

    private void audit(Run run, String type, Map<String, Object> data, String stageId, Integer attempt, String actor) {
        AuditLog log = auditLogs.computeIfAbsent(run.id, id -> new AuditLog(store.auditPath(id)));
        logger.accept(log.append(type, actor, stageId, attempt, data));
    }

    private record GateCheck(String id, boolean retryable, java.util.function.Supplier<String> check) {
    }

    private record GateResult(boolean passed, List<Map<String, Object>> results, String reason, boolean retryable) {
    }

    /** Runs gate checks. A check returns null when satisfied, or a failure reason. */
    private static GateResult evaluateGates(List<GateCheck> gates) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        boolean retryable = true;
        for (GateCheck gate : gates) {
            String outcome;
            try {
                outcome = gate.check().get();
            } catch (RuntimeException e) {
                outcome = "check crashed: " + e.getMessage();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("gate", gate.id());
            result.put("passed", outcome == null);
            result.put("reason", outcome);
            results.add(result);
            if (outcome != null) {
                failures.add(gate.id() + ": " + outcome);
                retryable &= gate.retryable();
            }
        }
        return new GateResult(failures.isEmpty(), results, String.join("; ", failures), retryable);
    }

    private static OrchestratorException asOrchestratorException(Exception e) {
        if (e instanceof OrchestratorException oe) return oe;
        if (e instanceof InterruptedException) return new OrchestratorException.Aborted();
        return new OrchestratorException(e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long millisBetween(String from, String to) {
        return Duration.between(Instant.parse(from), Instant.parse(to)).toMillis();
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static java.util.concurrent.ThreadFactory daemonThreads(String prefix) {
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + System.nanoTime());
            thread.setDaemon(true);
            return thread;
        };
    }
}
