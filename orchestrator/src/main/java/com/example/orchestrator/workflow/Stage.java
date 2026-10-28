package com.example.orchestrator.workflow;

import java.util.List;

/** A stage in a run's plan: a template plus its place in the dependency graph. */
public record Stage(String id, String templateName, List<String> dependsOn, StageTemplate template) {
}
