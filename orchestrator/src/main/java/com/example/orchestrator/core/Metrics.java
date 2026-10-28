package com.example.orchestrator.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.orchestrator.model.Run;
import com.example.orchestrator.model.Run.RunStatus;
import com.example.orchestrator.model.StageState;

/**
 * Reliability metrics computed from run state and audit trails.
 *
 * <ul>
 *   <li>successRate: completed runs / finished runs (completed + failed)</li>
 *   <li>firstPassYield: stage executions that succeeded on their first attempt</li>
 *   <li>retryFrequency: retries / all attempts</li>
 *   <li>rollbackFrequency: runs with at least one rollback / runs started</li>
 *   <li>MTTR: mean time from a stage failing to that stage completing again
 *       (retry, fallback, or a human fix + resume)</li>
 *   <li>end-to-end latency: run start to finish; "active" excludes time spent
 *       waiting for human approval</li>
 * </ul>
 */
public final class Metrics {

    public record Report(int runs, int completed, int failed, int inProgress, Double successRate, Double firstPassYield, Double retryFrequency,
            Double rollbackFrequency, int fallbacksUsed, Double mttrMs, int recovered, int unrecovered, Double averageLatencyMs,
            Double averageActiveLatencyMs, Double averageApprovalWaitMs, int approvals, Map<String, Integer> policyFindings) {
    }

    private Metrics() {
    }

    public static Report compute(List<Run> runs, Map<String, List<AuditLog.Entry>> auditByRun) {
        long completed = runs.stream().filter(r -> r.status == RunStatus.COMPLETED).count();
        long failed = runs.stream().filter(r -> r.status == RunStatus.FAILED).count();
        long started = runs.stream().filter(r -> r.startedAt != null).count();

        int attempts = 0;
        int executions = 0;
        int firstPass = 0;
        for (Run run : runs) {
            for (StageState state : run.stages.values()) {
                if (state.attempts.isEmpty()) continue;
                executions++;
                attempts += state.attempts.size();
                if (Boolean.TRUE.equals(state.attempts.get(0).ok)) firstPass++;
            }
        }

        List<Long> recoveries = new ArrayList<>();
        List<Long> approvalWaits = new ArrayList<>();
        List<long[]> latencies = new ArrayList<>();
        int unrecovered = 0;
        int rollbackRuns = 0;
        int fallbacks = 0;
        Map<String, Integer> policyFindings = new LinkedHashMap<>(Map.of("block", 0, "approval", 0, "warn", 0));

        for (Run run : runs) {
            List<AuditLog.Entry> entries = auditByRun.getOrDefault(run.id, List.of());
            if (entries.stream().anyMatch(e -> e.type().equals("rollback.completed"))) rollbackRuns++;
            fallbacks += (int) entries.stream().filter(e -> e.type().equals("fallback.used")).count();
            entries.stream().filter(e -> e.type().equals("policy.evaluated") && Boolean.FALSE.equals(e.data().get("passed")))
                    .forEach(e -> policyFindings.merge(String.valueOf(e.data().get("severity")), 1, Integer::sum));

            // An incident opens at the first failure of a stage and closes when it next completes.
            Map<String, Long> open = new HashMap<>();
            Map<String, Long> requested = new HashMap<>();
            long waitMs = 0;
            for (AuditLog.Entry e : entries) {
                long ts = Instant.parse(e.ts()).toEpochMilli();
                switch (e.type()) {
                    case "attempt.failed", "stage.blocked" -> open.putIfAbsent(e.stageId(), ts);
                    case "stage.completed" -> {
                        Long since = open.remove(e.stageId());
                        if (since != null) recoveries.add(ts - since);
                    }
                    case "approval.requested" -> requested.put(String.valueOf(e.data().get("key")), ts);
                    case "approval.granted", "approval.rejected" -> {
                        Long since = requested.remove(String.valueOf(e.data().get("key")));
                        if (since != null) {
                            approvalWaits.add(ts - since);
                            waitMs += ts - since;
                        }
                    }
                    default -> {
                    }
                }
            }
            unrecovered += open.size();

            if (run.status == RunStatus.COMPLETED) {
                long total = Instant.parse(run.finishedAt).toEpochMilli() - Instant.parse(run.startedAt).toEpochMilli();
                latencies.add(new long[] {total, Math.max(0, total - waitMs)});
            }
        }

        return new Report(runs.size(), (int) completed, (int) failed, (int) (runs.size() - completed - failed),
                ratio(completed, completed + failed), ratio(firstPass, executions), ratio(attempts - executions, attempts),
                ratio(rollbackRuns, started), fallbacks, average(recoveries), recoveries.size(), unrecovered,
                average(latencies.stream().map(l -> l[0]).toList()), average(latencies.stream().map(l -> l[1]).toList()),
                average(approvalWaits), approvalWaits.size(), policyFindings);
    }

    private static Double ratio(long part, long whole) {
        return whole == 0 ? null : (double) part / whole;
    }

    private static Double average(List<Long> values) {
        return values.isEmpty() ? null : values.stream().mapToLong(Long::longValue).average().orElse(0);
    }
}
