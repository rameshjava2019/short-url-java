package com.example.orchestrator.model;

/** The outcome of one guardrail rule for one stage. */
public record PolicyResult(String ruleId, String title, String category, String severity, String approverRole, boolean passed,
        String message) {
}
