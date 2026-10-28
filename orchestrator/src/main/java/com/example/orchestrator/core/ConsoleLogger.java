package com.example.orchestrator.core;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Turns audit entries into readable console lines while a run executes.
 * The audit file remains the source of truth; this is just a live view.
 */
public final class ConsoleLogger implements Consumer<AuditLog.Entry> {

    public static final String RESET = "\u001B[0m";
    public static final String DIM = "\u001B[2m";
    public static final String RED = "\u001B[31m";
    public static final String GREEN = "\u001B[32m";
    public static final String YELLOW = "\u001B[33m";
    public static final String BLUE = "\u001B[34m";
    public static final String MAGENTA = "\u001B[35m";
    public static final String CYAN = "\u001B[36m";

    private record Line(String color, String text) {
    }

    private static final Map<String, Function<AuditLog.Entry, Line>> FORMATS = Map.ofEntries(
            Map.entry("run.created", e -> new Line(CYAN, "run created for scenario \"" + e.data().get("scenario") + "\"")),
            Map.entry("run.started", e -> new Line(CYAN, "run started")),
            Map.entry("run.resumed", e -> new Line(CYAN, "run resumed")),
            Map.entry("replan.upstream_changed", e -> new Line(MAGENTA, "↻ upstream input " + e.data().get("artifactId") + " changed (v" + e.data().get("version") + ") → re-planning")),
            Map.entry("replan.requested", e -> new Line(MAGENTA, "↻ re-plan requested from " + e.stageId() + ": " + e.data().get("note"))),
            Map.entry("replan.stage_reset", e -> new Line(MAGENTA, "↻ " + e.stageId() + " reset (" + e.data().get("reason") + ")")),
            Map.entry("plan.stage_added", e -> new Line(MAGENTA, "+ plan: added stage \"" + e.data().get("added") + "\" (" + e.data().get("reason") + ")")),
            Map.entry("plan.stage_removed", e -> new Line(MAGENTA, "- plan: removed stage \"" + e.data().get("removed") + "\"")),
            Map.entry("gate.entry.failed", e -> new Line(RED, "✖ " + e.stageId() + " entry gate failed")),
            Map.entry("gate.exit.failed", e -> new Line(YELLOW, "! " + e.stageId() + " exit gate failed")),
            Map.entry("stage.started", e -> new Line(BLUE, "▶ " + e.stageId() + " started")),
            Map.entry("attempt.failed", e -> new Line(YELLOW, "  " + e.stageId() + " attempt " + e.attempt() + " failed: " + e.data().get("error"))),
            Map.entry("retry.scheduled", e -> new Line(YELLOW, "  " + e.stageId() + " retrying in " + e.data().get("delayMs") + " ms")),
            Map.entry("fallback.used", e -> new Line(YELLOW, "  " + e.stageId() + " falling back from " + e.data().get("from") + " to " + e.data().get("to"))),
            Map.entry("agent.log", e -> new Line(DIM, "  " + e.stageId() + ": " + e.data().get("message"))),
            Map.entry("policy.evaluated", e -> Boolean.TRUE.equals(e.data().get("passed")) ? null
                    : new Line("warn".equals(e.data().get("severity")) ? YELLOW : MAGENTA,
                            "  policy " + e.data().get("ruleId") + " (" + e.data().get("severity") + "): " + e.data().get("message"))),
            Map.entry("stage.completed", e -> new Line(GREEN, "✔ " + e.stageId() + " completed (" + seconds(e.data().get("durationMs")) + ")")),
            Map.entry("stage.failed", e -> new Line(RED, "✖ " + e.stageId() + " failed: " + e.data().get("error"))),
            Map.entry("stage.blocked", e -> new Line(RED, "✖ " + e.stageId() + " blocked: " + e.data().getOrDefault("reason", e.data().get("error")))),
            Map.entry("stage.aborted", e -> new Line(YELLOW, "■ " + e.stageId() + " aborted")),
            Map.entry("approval.requested", e -> new Line(MAGENTA, "⏸ " + e.stageId() + " needs approval (" + String.join(" or ", list(e.data().get("roles")))
                    + "): " + String.join("; ", list(e.data().get("reasons"))))),
            Map.entry("approval.granted", e -> new Line(GREEN, "✔ " + e.stageId() + " approved by " + e.actor().replace("human:", "")
                    + (e.data().get("choice") != null ? " (choice: " + e.data().get("choice") + ")" : ""))),
            Map.entry("approval.rejected", e -> new Line(RED, "✖ " + e.stageId() + " rejected by " + e.actor().replace("human:", "") + ": " + e.data().get("reason"))),
            Map.entry("rollback.started", e -> new Line(YELLOW, "⟲ rolling back " + e.stageId())),
            Map.entry("rollback.completed", e -> new Line(YELLOW, "⟲ " + e.stageId() + " rolled back")),
            Map.entry("rollback.failed", e -> new Line(RED, "✖ rollback of " + e.stageId() + " failed: " + e.data().get("error"))),
            Map.entry("run.stop_requested", e -> new Line(YELLOW, "■ safe-stop requested by " + e.data().get("by") + ": " + e.data().get("reason"))),
            Map.entry("run.paused", e -> new Line(MAGENTA, "⏸ run paused, waiting for human approval")),
            Map.entry("run.completed", e -> new Line(GREEN, "✔ run completed in " + seconds(e.data().get("durationMs")))),
            Map.entry("run.failed", e -> new Line(RED, "✖ run failed: " + e.data().get("reason"))),
            Map.entry("run.stopped", e -> new Line(YELLOW, "■ run stopped: " + e.data().get("reason"))));

    @Override
    public void accept(AuditLog.Entry entry) {
        Function<AuditLog.Entry, Line> format = FORMATS.get(entry.type());
        if (format == null) return;
        Line line = format.apply(entry);
        if (line == null) return;
        System.out.println(DIM + entry.ts().substring(11, 19) + RESET + " " + line.color() + line.text() + RESET);
    }

    private static String seconds(Object millis) {
        return millis instanceof Number n ? String.format("%.1f s", n.doubleValue() / 1000) : "?";
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Object value) {
        return value instanceof List<?> items ? (List<String>) items : List.of();
    }
}
