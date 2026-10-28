package com.example.orchestrator.model;

import java.util.ArrayList;
import java.util.List;

/** Execution state of one stage. */
public class StageState {

    public enum Status { PENDING, RUNNING, AWAITING_APPROVAL, COMPLETED, FAILED, BLOCKED, REJECTED, ROLLED_BACK }

    public Status status = Status.PENDING;
    public List<Attempt> attempts = new ArrayList<>();
    /** Hash of the exact inputs (artifact versions + notes) the stage last ran with. */
    public String inputFingerprint;
    public String outputFingerprint;
    public String startedAt;
    public String completedAt;
    public String failedAt;
    public String error;
    public List<PlanChange> pendingPlanChanges = new ArrayList<>();

    public static class Attempt {
        public int number;
        public String agent;
        public boolean fallback;
        public boolean injected;
        public String startedAt;
        public String endedAt;
        public Boolean ok;
        public String error;
    }
}
