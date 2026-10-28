package com.example.orchestrator.core;

/**
 * Errors the engine understands. {@code retryable} tells the engine whether
 * another attempt could succeed (transient) or is pointless (deterministic).
 */
public class OrchestratorException extends RuntimeException {

    private final boolean retryable;

    public OrchestratorException(String message) {
        this(message, true);
    }

    public OrchestratorException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }

    /** A failure that will happen again on retry (bad input, missing plan, compile error...). */
    public static class NonRetryable extends OrchestratorException {
        public NonRetryable(String message) {
            super(message, false);
        }
    }

    /** A guardrail was violated. Never retried, never handled by a fallback agent. */
    public static class PolicyViolation extends OrchestratorException {
        private final String ruleId;

        public PolicyViolation(String ruleId, String message) {
            super("[" + ruleId + "] " + message, false);
            this.ruleId = ruleId;
        }

        public String getRuleId() {
            return ruleId;
        }
    }

    public static class GateFailed extends OrchestratorException {
        public GateFailed(String message, boolean retryable) {
            super(message, retryable);
        }
    }

    public static class StageTimeout extends OrchestratorException {
        public StageTimeout(String stageId, long timeoutMs) {
            super("Stage \"" + stageId + "\" timed out after " + timeoutMs + " ms", true);
        }
    }

    public static class Aborted extends OrchestratorException {
        public Aborted() {
            super("Aborted by safe-stop", false);
        }
    }
}
