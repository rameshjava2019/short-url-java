package com.example.orchestrator.scenarios;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The available scenarios. Each requirement document lives in
 * orchestrator/scenarios/&lt;id&gt;/requirement.md (editable; edits trigger a re-plan
 * on resume). The reviewed change plans are Java classes in this package.
 */
public final class Scenarios {

    private final Map<String, Scenario> byId = new LinkedHashMap<>();

    public Scenarios(Path projectRoot) {
        Path dir = projectRoot.resolve("orchestrator/scenarios");
        add(new Scenario("greenfield-stats", "New feature: global usage statistics endpoint", "greenfield",
                dir.resolve("greenfield-stats/requirement.md"), List.of(GreenfieldStatsPlan.create())));
        add(new Scenario("brownfield-expiry", "Enhancement: optional link expiry (410 Gone)", "brownfield",
                dir.resolve("brownfield-expiry/requirement.md"), List.of(BrownfieldExpiryPlan.create())));
        add(new Scenario("ambiguous-safer-links", "Ambiguous request: \"make short links safer\"", "ambiguous",
                dir.resolve("ambiguous-safer-links/requirement.md"), List.of(SaferLinksPlans.domainBlocklist())));
    }

    private void add(Scenario scenario) {
        byId.put(scenario.id(), scenario);
    }

    public List<Scenario> list() {
        return List.copyOf(byId.values());
    }

    public Scenario get(String id) {
        Scenario scenario = byId.get(id);
        if (scenario == null) {
            throw new IllegalArgumentException("Unknown scenario \"" + id + "\". Available: " + String.join(", ", byId.keySet()));
        }
        return scenario;
    }
}
