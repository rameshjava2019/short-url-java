package com.example.orchestrator.agents;

import com.example.orchestrator.artifacts.Artifacts.Design;
import com.example.orchestrator.artifacts.Artifacts.SecurityReviewRecord;
import com.example.orchestrator.model.Approval;

/**
 * The work of this stage is the human approval itself (requested by the engine
 * before the stage runs). The agent records who signed off on what.
 */
public final class ReviewGateAgent implements Agent {

    @Override
    public String name() {
        return "review-gate";
    }

    @Override
    public AgentResult run(AgentContext context) {
        Approval approval;
        synchronized (context.ctx().run()) {
            approval = context.ctx().run().approvals.get(context.stage().id() + ":before");
        }
        Design design = context.ctx().get("design", Design.class);
        String approver = approval == null ? null : approval.decidedBy;
        return AgentResult.of("securityReviewRecord", new SecurityReviewRecord(design.sensitiveFiles(), approver,
                approval == null ? null : approval.decidedAt, approval == null ? null : approval.reason))
                .summary("signed off by " + (approver == null ? "unknown" : approver));
    }
}
