package com.example.orchestrator.model;

import java.util.ArrayList;
import java.util.List;

/** A human checkpoint: requested by the engine, decided by a person with the right role. */
public class Approval {

    public enum Kind { BEFORE, EXIT }

    public enum Status { PENDING, GRANTED, REJECTED }

    public String key;
    public String stageId;
    /** BEFORE: approve a high-impact stage before it runs. EXIT: approve a stage's outputs flagged by policy. */
    public Kind kind;
    public Status status = Status.PENDING;
    public List<String> roles = new ArrayList<>();
    public List<String> reasons = new ArrayList<>();
    /** Choices offered to the approver (e.g. interpretations of an ambiguous requirement). */
    public List<Option> options;
    /** The approval only counts for exactly these inputs (BEFORE) or outputs (EXIT). */
    public String fingerprint;
    public String requestedAt;
    public String decidedBy;
    public String decidedAt;
    public String reason;
    public String choice;
    public boolean processed;

    public record Option(String id, String description) {
    }
}
