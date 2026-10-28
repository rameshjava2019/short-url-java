package com.example.orchestrator.model;

import java.util.ArrayList;
import java.util.List;

/** A change to the workflow proposed by an agent: add a stage from an allowed template. */
public class PlanChange {

    public String action = "addStage";
    public String id;
    public String template;
    public List<String> dependsOn = new ArrayList<>();
    /** Existing stages that must now wait for the new stage. */
    public List<String> before = new ArrayList<>();
    public String reason;

    public PlanChange() {
    }

    public static PlanChange addStage(String id, String template, List<String> dependsOn, List<String> before, String reason) {
        PlanChange change = new PlanChange();
        change.id = id;
        change.template = template;
        change.dependsOn = new ArrayList<>(dependsOn);
        change.before = new ArrayList<>(before);
        change.reason = reason;
        return change;
    }
}
