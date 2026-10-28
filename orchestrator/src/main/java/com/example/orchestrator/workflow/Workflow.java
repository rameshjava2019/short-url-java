package com.example.orchestrator.workflow;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.orchestrator.model.Run.PlanEntry;

/**
 * A workflow: stage templates, the initial dependency graph, and which
 * templates agents are allowed to add at run time.
 */
public record Workflow(Map<String, StageTemplate> templates, List<PlanEntry> initialPlan, Set<String> agentAddableTemplates) {
}
