package com.example.orchestrator.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.example.orchestrator.agents.RequirementsAgent;
import com.example.orchestrator.artifacts.Artifacts.Spec;
import com.example.orchestrator.policies.WriteGuard;

/** Guardrails and requirement parsing that the scenarios depend on. */
class WriteGuardAndRequirementsTest {

    @ParameterizedTest
    @ValueSource(strings = {"/etc/passwd", "../outside.txt", "src/../../escape", "orchestrator/approvers.json", ".orchestrator/runs/x",
            "mvnw", ".env", "target/classes/x.class", "orchestrate"})
    void writeGuardRejectsUnsafeOrProtectedPaths(String path) {
        assertThrows(OrchestratorException.PolicyViolation.class, () -> WriteGuard.assertWriteAllowed(path));
    }

    @Test
    void writeGuardAllowsNormalSourceFiles() {
        assertEquals("src/main/java/X.java", WriteGuard.assertWriteAllowed("src/main/java/./X.java"));
        assertTrue(WriteGuard.isSecuritySensitive("src/main/resources/application.yml"));
    }

    @Test
    void explicitAcceptanceCriteriaMeanNoAmbiguity() {
        Spec spec = RequirementsAgent.analyze("""
                # Stats

                Ops wants totals.

                ## Acceptance criteria

                - `GET /api/stats` returns totals.
                - It is safe to call without a token.
                """);
        assertEquals(2, spec.acceptanceCriteria().size());
        assertTrue(spec.ambiguities().isEmpty(), "the word safe is fine when criteria are measurable");
        assertEquals("Ops wants totals.", spec.summary());
    }

    @Test
    void vagueWordingWithoutCriteriaIsFlaggedWithOptions() {
        Spec spec = RequirementsAgent.analyze("# Safer\n\nShort links should be safer for the people who click them.\n");
        assertEquals(1, spec.acceptanceCriteria().size());
        assertTrue(spec.acceptanceCriteria().get(0).derived());
        assertEquals("safer", spec.ambiguities().get(0).term());
        assertEquals(3, spec.ambiguities().get(0).options().size());
    }
}
