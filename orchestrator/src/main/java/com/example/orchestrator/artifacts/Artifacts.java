package com.example.orchestrator.artifacts;

import java.util.List;
import java.util.Map;

import com.example.orchestrator.model.Approval;

/**
 * The artifacts stages hand to each other. Each is stored as JSON, versioned and
 * hashed by the run context, so downstream stages can tell exactly what changed.
 */
public final class Artifacts {

    private Artifacts() {
    }

    // ---- Requirements ----

    /** The requirement document as the stakeholder wrote it (external input). */
    public record RequirementSource(String text) {
    }

    public record Criterion(String id, String text, boolean derived, boolean refined) {
    }

    public record Ambiguity(String term, String question, List<Approval.Option> options) {
    }

    /** The normalized engineering spec produced by requirement analysis. */
    public record Spec(String title, String summary, List<Criterion> acceptanceCriteria, List<Ambiguity> ambiguities,
            List<String> keywords, String analyzer, String reviewerNote) {
    }

    /** A human's answer to an ambiguity, chosen at the requirements checkpoint. */
    public record Clarification(String choice, String decidedBy, String reason) {
    }

    // ---- Design ----

    public record Task(String id, String title, List<String> dependsOn) {
    }

    public record Trace(String criterion, String text, List<String> tasks) {
    }

    public record ImpactedFile(String file, String change, List<String> dependents, boolean securitySensitive) {
    }

    public record RelatedFile(String file, int hits) {
    }

    public record DocExpectation(String file, String contains, String why, Boolean met) {
        public DocExpectation withMet(boolean value) {
            return new DocExpectation(file, contains, why, value);
        }
    }

    public record Design(boolean automatable, String reason, List<String> handoffTasks, String planId, String planHash, String title,
            String approach, List<Criterion> criteria, List<Task> tasks, List<Trace> traceability, List<ImpactedFile> impactedFiles,
            List<RelatedFile> relatedFiles, List<String> sensitiveFiles, boolean schemaChange, int estimatedLines, List<String> risks,
            List<String> assumptions, List<DocExpectation> docsExpectations, String rollbackPlan) {

        public static Design handoff(String reason, List<String> tasks) {
            return new Design(false, reason, tasks, null, null, null, null, List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), false, 0, List.of(), List.of(), List.of(), null);
        }
    }

    // ---- Implementation ----

    public record FileChange(String file, String change, int added, int removed) {
    }

    public record Line(String file, String text) {
    }

    public record Totals(int files, int added, int removed) {
    }

    public record ChangeSet(String workspace, String planId, List<FileChange> files, List<Line> addedLines, List<Line> removedLines,
            Totals totals, String patchFile, List<String> compileErrors) {
    }

    // ---- Validation ----

    public record TestReport(int total, int passed, int failed, int skipped, long durationMs, List<String> failures,
            int baselineTestCount, int testCount, String outputTail) {
    }

    public record Finding(String rule, String severity, String file, int line, String message) {
    }

    public record DependencyAudit(boolean skipped, String reason, int checked, List<String> vulnerable) {
    }

    public record SecurityReport(String mode, List<Finding> findings, DependencyAudit dependencyAudit) {
    }

    public record DocsReport(List<DocExpectation> expectations, List<DocExpectation> missing) {
    }

    public record CompatibilityReport(boolean compatible, List<String> issues, List<String> newFields, List<String> notes) {
    }

    public record SecurityReviewRecord(List<String> files, String approvedBy, String approvedAt, String reason) {
    }

    // ---- Readiness and release ----

    public record Check(String check, boolean passed, String detail) {
    }

    public record EngineeringSummary(String verdict, List<String> blockers, List<Check> checks, List<Trace> traceability,
            String markdownFile) {
    }

    public record ReleaseReport(String target, String mode, List<Map<String, Object>> files, Map<String, Object> verification,
            String releasedAt) {
    }
}
