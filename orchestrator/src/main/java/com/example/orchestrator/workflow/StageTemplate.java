package com.example.orchestrator.workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.example.orchestrator.agents.AgentResult;
import com.example.orchestrator.core.RunContext;
import com.example.orchestrator.model.RunOptions;

/**
 * Definition of a kind of stage: which agent runs it, what it consumes and
 * produces, its gates, retry policy, timeout and human checkpoint.
 *
 * Gate checks return null when satisfied, or a short reason string.
 */
public record StageTemplate(
        String title,
        Function<RunOptions, String> agent,
        String fallbackAgent,
        List<String> consumes,
        List<String> produces,
        int maxAttempts,
        Duration timeout,
        boolean sideEffects,
        List<EntryGate> entryGates,
        List<ExitGate> exitGates,
        Function<RunContext, ApprovalRequirement> approvalBefore) {

    /** Precondition checked before the stage starts. */
    public record EntryGate(String id, Function<RunContext, String> check) {
    }

    /** The stage's definition of done, checked on the agent's result. */
    public record ExitGate(String id, boolean retryable, BiFunction<AgentResult, RunContext, String> check) {
    }

    /** A human checkpoint required before a high-impact stage runs. */
    public record ApprovalRequirement(String role, String reason) {
    }

    public static Builder builder(String title) {
        return new Builder(title);
    }

    public static final class Builder {
        private final String title;
        private Function<RunOptions, String> agent;
        private String fallbackAgent;
        private List<String> consumes = List.of();
        private List<String> produces = List.of();
        private int maxAttempts = 1;
        private Duration timeout = Duration.ofSeconds(60);
        private boolean sideEffects;
        private final List<EntryGate> entryGates = new ArrayList<>();
        private final List<ExitGate> exitGates = new ArrayList<>();
        private Function<RunContext, ApprovalRequirement> approvalBefore;

        private Builder(String title) {
            this.title = title;
        }

        public Builder agent(String name) {
            this.agent = options -> name;
            return this;
        }

        /** Choose the agent from the run options (e.g. the Claude agent when --llm is set). */
        public Builder agent(Function<RunOptions, String> chooser) {
            this.agent = chooser;
            return this;
        }

        public Builder fallbackAgent(String name) {
            this.fallbackAgent = name;
            return this;
        }

        public Builder consumes(String... artifactIds) {
            this.consumes = List.of(artifactIds);
            return this;
        }

        public Builder produces(String... artifactIds) {
            this.produces = List.of(artifactIds);
            return this;
        }

        public Builder maxAttempts(int attempts) {
            this.maxAttempts = attempts;
            return this;
        }

        public Builder timeout(Duration duration) {
            this.timeout = duration;
            return this;
        }

        public Builder sideEffects() {
            this.sideEffects = true;
            return this;
        }

        public Builder entryGate(String id, Function<RunContext, String> check) {
            entryGates.add(new EntryGate(id, check));
            return this;
        }

        public Builder exitGate(String id, boolean retryable, BiFunction<AgentResult, RunContext, String> check) {
            exitGates.add(new ExitGate(id, retryable, check));
            return this;
        }

        public Builder approvalBefore(Function<RunContext, ApprovalRequirement> requirement) {
            this.approvalBefore = requirement;
            return this;
        }

        public StageTemplate build() {
            return new StageTemplate(title, agent, fallbackAgent, consumes, produces, maxAttempts, timeout, sideEffects,
                    List.copyOf(entryGates), List.copyOf(exitGates), approvalBefore);
        }
    }
}
