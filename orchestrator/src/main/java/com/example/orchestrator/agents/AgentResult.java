package com.example.orchestrator.agents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.orchestrator.model.Approval;
import com.example.orchestrator.model.PlanChange;

/** What an agent returns. Built fluently: {@code AgentResult.of("spec", spec).decision(...).summary(...)}. */
public final class AgentResult {

    public record DecisionInput(String decision, String rationale, List<String> alternatives) {
    }

    private final Map<String, Object> artifacts = new LinkedHashMap<>();
    private final List<DecisionInput> decisions = new ArrayList<>();
    private final List<PlanChange> planChanges = new ArrayList<>();
    private List<Approval.Option> approvalOptions;
    private String summary = "";

    public static AgentResult of(String artifactId, Object content) {
        return new AgentResult().artifact(artifactId, content);
    }

    public AgentResult artifact(String artifactId, Object content) {
        artifacts.put(artifactId, content);
        return this;
    }

    public AgentResult decision(String decision, String rationale) {
        return decision(decision, rationale, List.of());
    }

    public AgentResult decision(String decision, String rationale, List<String> alternatives) {
        decisions.add(new DecisionInput(decision, rationale, alternatives));
        return this;
    }

    public AgentResult planChange(PlanChange change) {
        planChanges.add(change);
        return this;
    }

    /** Choices to offer a human if a policy pauses the stage for approval. */
    public AgentResult approvalOptions(List<Approval.Option> options) {
        this.approvalOptions = options == null || options.isEmpty() ? null : List.copyOf(options);
        return this;
    }

    public AgentResult summary(String text) {
        this.summary = text;
        return this;
    }

    public Map<String, Object> artifacts() {
        return artifacts;
    }

    @SuppressWarnings("unchecked")
    public <T> T artifact(String artifactId) {
        return (T) artifacts.get(artifactId);
    }

    public List<DecisionInput> decisions() {
        return decisions;
    }

    public List<PlanChange> planChanges() {
        return planChanges;
    }

    public List<Approval.Option> approvalOptions() {
        return approvalOptions;
    }

    public String summary() {
        return summary;
    }
}
