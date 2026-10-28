package com.example.orchestrator.core;

import java.util.List;
import java.util.function.Function;

import com.example.orchestrator.model.PolicyResult;

/**
 * Evaluates guardrail rules against the run context when a stage finishes.
 */
public final class PolicyEngine {

    public enum Severity {
        /** The run stops (safe-stop + rollback); a human must change something. */
        BLOCK,
        /** The run pauses until someone with the rule's approver role approves. */
        APPROVAL,
        /** Recorded in the audit trail and the engineering summary only. */
        WARN
    }

    /**
     * One rule. {@code check} returns a violation message, or null when compliant.
     */
    public record Rule(String id, String title, String category, Severity severity, String approverRole, List<String> appliesTo,
            Function<RunContext, String> check) {
    }

    public record Summary(List<PolicyResult> blocked, List<PolicyResult> approvals, List<PolicyResult> warnings) {
    }

    private final List<Rule> rules;

    public PolicyEngine(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public List<PolicyResult> evaluate(String stageId, RunContext ctx) {
        return rules.stream().filter(rule -> rule.appliesTo().contains(stageId)).map(rule -> {
            String message;
            try {
                message = rule.check().apply(ctx);
            } catch (RuntimeException e) {
                // A rule that crashes is treated as a violation: fail closed, not open.
                message = "Rule could not be evaluated: " + e.getMessage();
            }
            return new PolicyResult(rule.id(), rule.title(), rule.category(), rule.severity().name().toLowerCase(), rule.approverRole(),
                    message == null, message);
        }).toList();
    }

    public static Summary summarize(List<PolicyResult> results) {
        List<PolicyResult> violations = results.stream().filter(r -> !r.passed()).toList();
        return new Summary(
                violations.stream().filter(r -> r.severity().equals("block")).toList(),
                violations.stream().filter(r -> r.severity().equals("approval")).toList(),
                violations.stream().filter(r -> r.severity().equals("warn")).toList());
    }
}
