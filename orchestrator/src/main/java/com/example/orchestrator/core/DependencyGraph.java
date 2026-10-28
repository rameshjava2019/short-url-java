package com.example.orchestrator.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.example.orchestrator.model.Run.PlanEntry;
import com.example.orchestrator.model.StageState.Status;

/**
 * A directed acyclic graph of stages. Construction validates the graph:
 * duplicate ids, unknown dependencies and cycles throw.
 */
public final class DependencyGraph {

    private final Map<String, List<String>> dependencies = new LinkedHashMap<>();
    private final List<String> order;

    public DependencyGraph(List<PlanEntry> entries) {
        for (PlanEntry entry : entries) {
            if (dependencies.put(entry.id, List.copyOf(entry.dependsOn)) != null) {
                throw new IllegalArgumentException("Duplicate stage id \"" + entry.id + "\"");
            }
        }
        dependencies.forEach((id, deps) -> deps.forEach(dep -> {
            if (!dependencies.containsKey(dep)) {
                throw new IllegalArgumentException("Stage \"" + id + "\" depends on unknown stage \"" + dep + "\"");
            }
        }));
        order = topologicalOrder();
    }

    // Kahn's algorithm. Anything left over at the end is part of a cycle.
    private List<String> topologicalOrder() {
        Map<String, Integer> remaining = new LinkedHashMap<>();
        dependencies.forEach((id, deps) -> remaining.put(id, deps.size()));
        Deque<String> queue = new ArrayDeque<>();
        remaining.forEach((id, count) -> {
            if (count == 0) queue.add(id);
        });

        List<String> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            result.add(id);
            for (String dependent : dependentsOf(id)) {
                if (remaining.merge(dependent, -1, Integer::sum) == 0) queue.add(dependent);
            }
        }

        if (result.size() != dependencies.size()) {
            List<String> inCycle = dependencies.keySet().stream().filter(id -> !result.contains(id)).toList();
            throw new IllegalArgumentException("Dependency cycle detected between stages: " + String.join(", ", inCycle));
        }
        return List.copyOf(result);
    }

    public List<String> order() {
        return order;
    }

    public List<String> dependenciesOf(String id) {
        return dependencies.get(id);
    }

    public List<String> dependentsOf(String id) {
        return dependencies.entrySet().stream().filter(e -> e.getValue().contains(id)).map(Map.Entry::getKey).toList();
    }

    /** Every stage {@code id} directly or indirectly depends on. */
    public Set<String> upstreamOf(String id) {
        Set<String> result = new LinkedHashSet<>();
        Deque<String> toVisit = new ArrayDeque<>(dependenciesOf(id));
        while (!toVisit.isEmpty()) {
            String current = toVisit.poll();
            if (result.add(current)) toVisit.addAll(dependenciesOf(current));
        }
        return result;
    }

    /**
     * Stages that can start now: still pending, and every upstream stage completed.
     * This is the synchronization point for parallel branches (a stage waits for all
     * its dependencies), and it stops a stage from running while something further
     * upstream is being re-planned.
     */
    public List<String> readyStages(Function<String, Status> statusOf) {
        return order.stream()
                .filter(id -> statusOf.apply(id) == Status.PENDING)
                .filter(id -> upstreamOf(id).stream().allMatch(dep -> statusOf.apply(dep) == Status.COMPLETED))
                .toList();
    }

    public String toMermaid(Function<String, Status> statusOf) {
        StringBuilder out = new StringBuilder("graph TD\n");
        for (String id : order) {
            out.append("  ").append(id).append("[\"").append(id).append("<br/><small>")
                    .append(statusOf.apply(id).name().toLowerCase()).append("</small>\"]\n");
            for (String dep : dependenciesOf(id)) {
                out.append("  ").append(dep).append(" --> ").append(id).append('\n');
            }
        }
        return out.toString();
    }
}
