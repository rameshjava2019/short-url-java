package com.example.orchestrator.agents;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The agent registry, by name. */
public final class Agents {

    private Agents() {
    }

    public static Map<String, Agent> all() {
        List<Agent> agents = List.of(
                new RequirementsAgent(),
                new LlmRequirementsAgent(),
                new DesignAgent(),
                new ImplementationAgent(),
                new TestAgent(),
                new SecurityAgent(),
                SecurityAgent.offline(),
                new DocsAgent(),
                new CompatibilityAgent(),
                new ReviewGateAgent(),
                new ReadinessAgent(),
                new ReleaseAgent());
        Map<String, Agent> byName = new LinkedHashMap<>();
        agents.forEach(agent -> byName.put(agent.name(), agent));
        return byName;
    }
}
