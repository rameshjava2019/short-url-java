package com.example.orchestrator.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.orchestrator.agents.Agent;
import com.example.orchestrator.agents.AgentContext;
import com.example.orchestrator.agents.AgentResult;
import com.example.orchestrator.model.Approval;
import com.example.orchestrator.model.PlanChange;
import com.example.orchestrator.model.Run;
import com.example.orchestrator.model.Run.PlanEntry;
import com.example.orchestrator.model.Run.RunStatus;
import com.example.orchestrator.model.RunOptions;
import com.example.orchestrator.model.StageState.Status;
import com.example.orchestrator.scenarios.Scenario;
import com.example.orchestrator.workflow.StageTemplate;
import com.example.orchestrator.workflow.StageTemplate.ApprovalRequirement;
import com.example.orchestrator.workflow.Workflow;

/**
 * Engine behaviour tests with small fake agents (no real SDLC work), so each
 * orchestration feature can be checked quickly and deterministically.
 *
 * Workflow under test (a diamond):  spec → build → (testA ∥ testB) → release
 */
class OrchestratorTest {

    @TempDir
    Path dir;

    private Path requirement;
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Agent> agents = new LinkedHashMap<>();
    private final Map<String, StageTemplate> templates = new LinkedHashMap<>();
    private final List<PolicyEngine.Rule> rules = new ArrayList<>();

    // ---- Fake building blocks ---------------------------------------------

    @FunctionalInterface
    interface Body {
        AgentResult run(AgentContext context) throws Exception;
    }

    private Agent agent(String name, Body body) {
        return agent(name, body, null);
    }

    private Agent agent(String name, Body body, Runnable rollback) {
        return new Agent() {
            public String name() {
                return name;
            }

            public AgentResult run(AgentContext context) throws Exception {
                calls.add(name);
                return body.run(context);
            }

            public void rollback(AgentContext context) {
                if (rollback != null) rollback.run();
            }
        };
    }

    private static StageTemplate.Builder stage(String agent, String[] consumes, String... produces) {
        return StageTemplate.builder(agent).agent(agent).consumes(consumes).produces(produces).timeout(Duration.ofSeconds(5));
    }

    private static String[] in(String... ids) {
        return ids;
    }

    @BeforeEach
    void setUp() throws IOException {
        requirement = dir.resolve("requirement.md");
        Files.writeString(requirement, "v1");

        agents.put("spec", agent("spec", ctx -> AgentResult.of("spec", Map.of("from", source(ctx)))));
        agents.put("build", agent("build", ctx -> AgentResult.of("build", Map.of("ok", true))));
        agents.put("testA", agent("testA", ctx -> {
            Thread.sleep(150);
            return AgentResult.of("reportA", Map.of("ok", true));
        }));
        agents.put("testB", agent("testB", ctx -> {
            Thread.sleep(150);
            return AgentResult.of("reportB", Map.of("ok", true));
        }));
        agents.put("release", agent("release", ctx -> AgentResult.of("release", Map.of("done", true))));
        agents.put("extra", agent("extra", ctx -> AgentResult.of("extra", Map.of())));

        templates.put("spec", stage("spec", in("requirementSource"), "spec").build());
        templates.put("build", stage("build", in("spec"), "build").sideEffects().build());
        templates.put("testA", stage("testA", in("build"), "reportA").build());
        templates.put("testB", stage("testB", in("build"), "reportB").build());
        templates.put("release", stage("release", in("reportA", "reportB"), "release").build());
        templates.put("extraCheck", stage("extra", in("build"), "extra").build());
    }

    private Orchestrator engine() {
        Workflow workflow = new Workflow(templates, List.of(
                new PlanEntry("spec", "spec", List.of(), null),
                new PlanEntry("build", "build", List.of("spec"), null),
                new PlanEntry("testA", "testA", List.of("build"), null),
                new PlanEntry("testB", "testB", List.of("build"), null),
                new PlanEntry("release", "release", List.of("testA", "testB"), null)), java.util.Set.of("extraCheck"));
        Scenario scenario = new Scenario("demo", "Demo", "test", requirement, List.of());
        Map<String, List<String>> approvers = Map.of("alice", List.of("release-manager"), "bob", List.of("qa"));
        return new Orchestrator(new RunStore(dir.resolve("store")), workflow, agents, new PolicyEngine(rules), id -> scenario, dir,
                entry -> { }, new Orchestrator.Settings(3, 1, 10, 10, 10, approvers));
    }

    private static String source(AgentContext ctx) {
        return ctx.ctx().get("requirementSource", Map.class).get("text").toString();
    }

    private static List<String> auditTypes(Orchestrator engine, String runId) {
        return AuditLog.readEntries(engine.store().auditPath(runId)).stream().map(AuditLog.Entry::type).toList();
    }

    private static int seq(Orchestrator engine, String runId, String type, String stageId) {
        return AuditLog.readEntries(engine.store().auditPath(runId)).stream()
                .filter(e -> e.type().equals(type) && stageId.equals(e.stageId())).findFirst().orElseThrow().seq();
    }

    private Run start(Orchestrator engine) {
        return engine.execute(engine.createRun("demo", new RunOptions()).id);
    }

    // ---- Tests ---------------------------------------------------------------

    @Test
    void runsADiamondGraphWithParallelBranchesSynchronised() {
        Orchestrator engine = engine();
        Run run = start(engine);

        assertEquals(RunStatus.COMPLETED, run.status);
        assertEquals(List.of("spec", "build"), calls.subList(0, 2));
        assertEquals("release", calls.get(calls.size() - 1));
        assertTrue(seq(engine, run.id, "stage.started", "testB") < seq(engine, run.id, "stage.completed", "testA"), "testA and testB overlap");
        assertTrue(seq(engine, run.id, "stage.started", "release") > seq(engine, run.id, "stage.completed", "testB"));
    }

    @Test
    void retriesATransientFailure() {
        AtomicInteger failuresLeft = new AtomicInteger(2);
        agents.put("build", agent("build", ctx -> {
            if (failuresLeft.getAndDecrement() > 0) throw new OrchestratorException("flaky network");
            return AgentResult.of("build", Map.of());
        }));
        templates.put("build", stage("build", in("spec"), "build").maxAttempts(3).build());

        Orchestrator engine = engine();
        Run run = start(engine);
        assertEquals(RunStatus.COMPLETED, run.status);
        assertEquals(3, run.stages.get("build").attempts.size());
        assertEquals(2, auditTypes(engine, run.id).stream().filter("retry.scheduled"::equals).count());
    }

    @Test
    void nonRetryableErrorSkipsRetriesButUsesTheFallback() {
        agents.put("build", agent("build", ctx -> {
            throw new OrchestratorException.NonRetryable("model refused");
        }));
        agents.put("buildSimple", agent("buildSimple", ctx -> AgentResult.of("build", Map.of("simple", true))));
        templates.put("build", stage("build", in("spec"), "build").maxAttempts(3).fallbackAgent("buildSimple").build());

        Orchestrator engine = engine();
        Run run = start(engine);
        assertEquals(RunStatus.COMPLETED, run.status);
        assertEquals(List.of("build", "buildSimple"), run.stages.get("build").attempts.stream().map(a -> a.agent).toList());
        assertTrue(auditTypes(engine, run.id).contains("fallback.used"));
    }

    @Test
    void terminalFailureHaltsTheRunAndRollsBackSideEffects() {
        List<String> rolledBack = new ArrayList<>();
        agents.put("build", agent("build", ctx -> AgentResult.of("build", Map.of()), () -> rolledBack.add("build")));
        agents.put("testA", agent("testA", ctx -> {
            throw new OrchestratorException.NonRetryable("3 tests failed");
        }));

        Run run = start(engine());
        assertEquals(RunStatus.FAILED, run.status);
        assertTrue(run.statusReason.contains("3 tests failed"));
        assertEquals(List.of("build"), rolledBack);
        assertEquals(Status.ROLLED_BACK, run.stages.get("build").status);
        assertEquals(Status.PENDING, run.stages.get("release").status);
    }

    @Test
    void exitGateFailureCountsAsAFailedAttempt() {
        templates.put("spec", stage("spec", in("requirementSource"), "spec").maxAttempts(2)
                .exitGate("has-criteria", true, (result, ctx) -> "no criteria").build());

        Orchestrator engine = engine();
        Run run = start(engine);
        assertEquals(RunStatus.FAILED, run.status);
        assertEquals(2, run.stages.get("spec").attempts.size());
        assertTrue(auditTypes(engine, run.id).contains("gate.exit.failed"));
    }

    @Test
    void pausesBeforeAHighImpactStageAndResumesWhenApproved() {
        templates.put("release", stage("release", in("reportA", "reportB"), "release")
                .approvalBefore(ctx -> new ApprovalRequirement("release-manager", "Production release")).build());

        Orchestrator engine = engine();
        Run run = start(engine);
        assertEquals(RunStatus.AWAITING_APPROVAL, run.status);
        assertFalse(calls.contains("release"));

        assertThrows(IllegalArgumentException.class, () -> engine.recordApproval(run.id, "release", "bob", true, null, null), "wrong role");
        engine.recordApproval(run.id, "release", "alice", true, "looks good", null);

        calls.clear();
        assertEquals(RunStatus.COMPLETED, engine.execute(run.id).status);
        assertEquals(List.of("release"), calls, "only the approved stage runs on resume");
    }

    @Test
    void rejectedApprovalFailsTheRunAndRollsBack() {
        List<String> rolledBack = new ArrayList<>();
        agents.put("build", agent("build", ctx -> AgentResult.of("build", Map.of()), () -> rolledBack.add("build")));
        templates.put("release", stage("release", in("reportA"), "release")
                .approvalBefore(ctx -> new ApprovalRequirement("release-manager", "x")).build());

        Orchestrator engine = engine();
        Run run = start(engine);
        engine.recordApproval(run.id, "release", "alice", false, "not this week", null);
        Run after = engine.execute(run.id);

        assertEquals(RunStatus.FAILED, after.status);
        assertTrue(after.statusReason.contains("not this week"));
        assertEquals(List.of("build"), rolledBack);
    }

    @Test
    void policyApprovalPausesAfterTheStageAndAChoiceBecomesAnArtifact() {
        agents.put("spec", agent("spec", ctx -> AgentResult.of("spec", Map.of("ambiguous", true))
                .approvalOptions(List.of(new Approval.Option("a", "Option A"), new Approval.Option("b", "Option B")))));
        agents.put("build", agent("build", ctx -> AgentResult.of("build",
                Map.of("chosen", ctx.ctx().get("spec.clarification", Map.class).get("choice")))));
        templates.put("build", stage("build", in("spec", "spec.clarification"), "build").build());
        rules.add(new PolicyEngine.Rule("REQ-1", "Clarify", "compliance", PolicyEngine.Severity.APPROVAL, "qa", List.of("spec"), ctx -> "ambiguous"));

        Orchestrator engine = engine();
        Run run = start(engine);
        assertEquals(RunStatus.AWAITING_APPROVAL, run.status);

        assertThrows(IllegalArgumentException.class, () -> engine.recordApproval(run.id, "spec", "bob", true, null, null), "needs --choice");
        engine.recordApproval(run.id, "spec", "bob", true, null, "b");
        Run after = engine.execute(run.id);

        assertEquals(RunStatus.COMPLETED, after.status);
        assertEquals("b", engine.contextFor(after).get("build", Map.class).get("chosen"));
    }

    @Test
    void blockPolicyStopsTheRun() {
        rules.add(new PolicyEngine.Rule("SEC-1", "No secrets", "security", PolicyEngine.Severity.BLOCK, null, List.of("build"), ctx -> "secret found"));
        Run run = start(engine());
        assertEquals(RunStatus.FAILED, run.status);
        assertEquals(Status.BLOCKED, run.stages.get("build").status);
        assertTrue(run.statusReason.contains("SEC-1"));
    }

    @Test
    void policyViolationFromAnAgentBlocksWithoutRetryOrFallback() {
        agents.put("build", agent("build", ctx -> {
            throw new OrchestratorException.PolicyViolation("CHG-004", "write to protected file");
        }));
        agents.put("buildSimple", agent("buildSimple", ctx -> AgentResult.of("build", Map.of())));
        templates.put("build", stage("build", in("spec"), "build").maxAttempts(3).fallbackAgent("buildSimple").build());

        Run run = start(engine());
        assertEquals(Status.BLOCKED, run.stages.get("build").status);
        assertEquals(1, run.stages.get("build").attempts.size());
    }

    @Test
    void safeStopAbortsInFlightWorkAndTheRunCanBeResumed() {
        Orchestrator[] engine = new Orchestrator[1];
        String[] runId = new String[1];
        agents.put("testA", agent("testA", ctx -> {
            engine[0].requestStop(runId[0], "alice", "incident");
            while (!ctx.signal().isStopped()) Thread.sleep(10);
            throw new OrchestratorException.Aborted();
        }));
        engine[0] = engine();
        runId[0] = engine[0].createRun("demo", new RunOptions()).id;

        Run stopped = engine[0].execute(runId[0]);
        assertEquals(RunStatus.STOPPED, stopped.status);
        assertTrue(stopped.statusReason.contains("incident"));
        assertEquals(Status.PENDING, stopped.stages.get("testA").status);
        assertTrue(auditTypes(engine[0], runId[0]).contains("run.stop_requested"));

        agents.put("testA", agent("testA", ctx -> AgentResult.of("reportA", Map.of("ok", true))));
        calls.clear();
        assertEquals(RunStatus.COMPLETED, engine[0].execute(runId[0]).status);
        assertFalse(calls.contains("spec") || calls.contains("build"), "completed stages are not redone");
    }

    @Test
    void replanReRunsOnlyTheStagesAChangedInputAffects() throws IOException {
        // spec output only depends on the first two characters of the requirement
        agents.put("spec", agent("spec", ctx -> AgentResult.of("spec", Map.of("major", source(ctx).substring(0, 2)))));
        Orchestrator engine = engine();
        Run run = start(engine);

        Files.writeString(requirement, "v2");
        calls.clear();
        assertEquals(RunStatus.COMPLETED, engine.execute(run.id).status);
        assertEquals(List.of("spec", "build"), calls.subList(0, 2));
        assertTrue(auditTypes(engine, run.id).contains("replan.upstream_changed"));

        Files.writeString(requirement, "v2 with a typo fixed");
        calls.clear();
        assertEquals(RunStatus.COMPLETED, engine.execute(run.id).status);
        assertEquals(List.of("spec"), calls, "spec output unchanged, so downstream stages are reused");
    }

    @Test
    void humanNoteReRunsTheStageAndInvalidatesItsApproval() {
        templates.put("build", stage("build", in("spec"), "build")
                .approvalBefore(ctx -> new ApprovalRequirement("qa", "review build")).build());
        agents.put("build", agent("build", ctx -> {
            Run.Note note = ctx.ctx().noteFor("build");
            return AgentResult.of("build", Map.of("note", note == null ? "" : note.note));
        }));
        Orchestrator engine = engine();
        Run run = start(engine);
        engine.recordApproval(run.id, "build", "bob", true, null, null);
        engine.execute(run.id);

        engine.requestReplan(run.id, "build", "use the new base image", "bob");
        assertEquals(RunStatus.AWAITING_APPROVAL, engine.execute(run.id).status, "inputs changed, so the old approval no longer counts");

        engine.recordApproval(run.id, "build", "bob", true, null, null);
        Run after = engine.execute(run.id);
        assertEquals(RunStatus.COMPLETED, after.status);
        assertEquals("use the new base image", engine.contextFor(after).get("build", Map.class).get("note"));
    }

    @Test
    void downstreamStagesWaitWhileAnUpstreamStageAwaitsApprovalAgain() throws IOException {
        templates.put("release", stage("release", in("spec", "reportA", "reportB"), "release").build());
        rules.add(new PolicyEngine.Rule("P", "review spec", "change-control", PolicyEngine.Severity.APPROVAL, "qa", List.of("spec"),
                ctx -> "review " + ctx.get("spec", Map.class).get("from")));
        Orchestrator engine = engine();
        Run run = start(engine);
        engine.recordApproval(run.id, "spec", "bob", true, null, null);
        engine.execute(run.id);

        Files.writeString(requirement, "v2");
        calls.clear();
        assertEquals(RunStatus.AWAITING_APPROVAL, engine.execute(run.id).status);
        assertEquals(List.of("spec"), calls, "release (which also consumes spec) must not run before spec is approved again");
    }

    @Test
    void exitApprovalStillCountsWhenTheReRunProducesIdenticalOutput() throws IOException {
        agents.put("spec", agent("spec", ctx -> AgentResult.of("spec", Map.of("constant", true))));
        rules.add(new PolicyEngine.Rule("P", "review spec", "change-control", PolicyEngine.Severity.APPROVAL, "qa", List.of("spec"), ctx -> "needs review"));
        Orchestrator engine = engine();
        Run run = start(engine);
        engine.recordApproval(run.id, "spec", "bob", true, null, null);
        engine.execute(run.id);

        Files.writeString(requirement, "v2");
        calls.clear();
        assertEquals(RunStatus.COMPLETED, engine.execute(run.id).status, "same output, same fingerprint, approval reused");
        assertEquals(List.of("spec"), calls);
    }

    @Test
    void agentsCanAddAllowedStagesButNotDisallowedOnes() {
        agents.put("build", agent("build", ctx -> AgentResult.of("build", Map.of())
                .planChange(PlanChange.addStage("extraCheck", "extraCheck", List.of("build"), List.of("release"), "schema changed"))));
        Run run = start(engine());
        assertEquals(RunStatus.COMPLETED, run.status);
        assertTrue(run.plan.stream().filter(e -> e.id.equals("release")).findFirst().orElseThrow().dependsOn.contains("extraCheck"));
        assertTrue(calls.indexOf("extra") < calls.indexOf("release"));

        calls.clear();
        agents.put("build", agent("build", ctx -> AgentResult.of("build", Map.of())
                .planChange(PlanChange.addStage("x", "release", List.of("build"), List.of(), "not allowed"))));
        Run blocked = start(engine());
        assertEquals(RunStatus.FAILED, blocked.status);
        assertTrue(blocked.statusReason.contains("CHG-007"));
    }

    @Test
    void stageTimeoutIsARetryableFailure() {
        agents.put("build", agent("build", ctx -> {
            Thread.sleep(60_000);
            return AgentResult.of("build", Map.of());
        }));
        templates.put("build", stage("build", in("spec"), "build").timeout(Duration.ofMillis(100)).maxAttempts(2).build());

        Run run = start(engine());
        assertEquals(RunStatus.FAILED, run.status);
        assertEquals(2, run.stages.get("build").attempts.size());
        assertTrue(run.stages.get("build").error.contains("timed out"));
    }

    @Test
    void refusesToResumeWhenTheAuditTrailWasTamperedWith() throws IOException {
        templates.put("release", stage("release", in("reportA"), "release")
                .approvalBefore(ctx -> new ApprovalRequirement("release-manager", "x")).build());
        Orchestrator engine = engine();
        Run run = start(engine);

        Path file = engine.store().auditPath(run.id);
        List<String> lines = new ArrayList<>(Files.readAllLines(file));
        lines.set(3, lines.get(3).replace("\"engine\"", "\"forged\""));
        Files.write(file, lines);

        assertFalse(AuditLog.verify(file).valid());
        Run after = engine.execute(run.id);
        assertEquals(RunStatus.STOPPED, after.status);
        assertTrue(after.statusReason.contains("audit trail failed verification"));
    }

    @Test
    void graphValidationRejectsCyclesAndUnknownDependencies() {
        assertThrows(IllegalArgumentException.class, () -> new DependencyGraph(List.of(
                new PlanEntry("a", "t", List.of("b"), null), new PlanEntry("b", "t", List.of("a"), null))));
        assertThrows(IllegalArgumentException.class, () -> new DependencyGraph(List.of(new PlanEntry("a", "t", List.of("zzz"), null))));
    }
}
