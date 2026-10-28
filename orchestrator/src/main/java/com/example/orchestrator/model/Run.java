package com.example.orchestrator.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The persisted state of one orchestrator run (written to state.json).
 * Plain public fields: this class is a data document, mapped by Jackson.
 */
public class Run {

    public String id;
    public String scenarioId;
    public String scenarioTitle;
    public RunStatus status = RunStatus.CREATED;
    public String statusReason;
    public String createdAt;
    public String startedAt;
    public String finishedAt;
    public RunOptions options = new RunOptions();

    /** The current dependency graph. Agents may add stages to it (dynamic re-plan). */
    public List<PlanEntry> plan = new ArrayList<>();
    public Map<String, StageState> stages = new LinkedHashMap<>();
    public Map<String, Approval> approvals = new LinkedHashMap<>();
    /** Versioned artifact records, by artifact id. Content lives in files under artifacts/. */
    public Map<String, List<ArtifactRecord>> artifacts = new LinkedHashMap<>();
    public List<Decision> decisions = new ArrayList<>();
    public Map<String, List<PolicyResult>> policyResults = new LinkedHashMap<>();
    /** Reviewer guidance per stage, added by "replan". Part of the stage's input fingerprint. */
    public Map<String, Note> notes = new LinkedHashMap<>();
    public int retries;
    /** Set when a stage fails terminally; the scheduler stops starting new stages. */
    public Halt halt;

    public enum RunStatus { CREATED, RUNNING, AWAITING_APPROVAL, STOPPED, FAILED, COMPLETED }

    public static class PlanEntry {
        public String id;
        public String template;
        public List<String> dependsOn = new ArrayList<>();
        /** The stage whose agent added this entry, or null for the initial plan. */
        public String addedBy;

        public PlanEntry() {
        }

        public PlanEntry(String id, String template, List<String> dependsOn, String addedBy) {
            this.id = id;
            this.template = template;
            this.dependsOn = new ArrayList<>(dependsOn);
            this.addedBy = addedBy;
        }
    }

    public static class Halt {
        public String stageId;
        public String reason;

        public Halt() {
        }

        public Halt(String stageId, String reason) {
            this.stageId = stageId;
            this.reason = reason;
        }
    }

    public static class Note {
        public String note;
        public String by;
        public String at;
    }
}
