package com.example.orchestrator.scenarios;

import java.nio.file.Path;
import java.util.List;

/**
 * A requirement document plus the reviewed change plans that can implement it.
 * The requirement file is re-read on every run/resume; editing it triggers a re-plan.
 */
public record Scenario(String id, String title, String type, Path requirementFile, List<ChangePlan> changePlans) {
}
