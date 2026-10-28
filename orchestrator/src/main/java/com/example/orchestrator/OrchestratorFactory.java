package com.example.orchestrator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.example.orchestrator.agents.Agents;
import com.example.orchestrator.core.AuditLog;
import com.example.orchestrator.core.Json;
import com.example.orchestrator.core.Orchestrator;
import com.example.orchestrator.core.PolicyEngine;
import com.example.orchestrator.core.RunStore;
import com.example.orchestrator.policies.SdlcPolicies;
import com.example.orchestrator.scenarios.Scenarios;
import com.example.orchestrator.workflow.SdlcWorkflow;
import com.fasterxml.jackson.core.type.TypeReference;

/** Wires the engine with the SDLC workflow, agents, policies and scenarios. */
public final class OrchestratorFactory {

    private OrchestratorFactory() {
    }

    public static Orchestrator create(Path projectRoot, Path storeDir, Consumer<AuditLog.Entry> logger) {
        Scenarios scenarios = new Scenarios(projectRoot);
        return new Orchestrator(new RunStore(storeDir), SdlcWorkflow.create(), Agents.all(), new PolicyEngine(SdlcPolicies.rules()),
                scenarios::get, projectRoot, logger, Orchestrator.Settings.defaults(approvers(projectRoot)));
    }

    /** Approvers and their roles, from orchestrator/approvers.json. */
    public static Map<String, List<String>> approvers(Path projectRoot) {
        try {
            return Json.MAPPER.readValue(projectRoot.resolve("orchestrator/approvers.json").toFile(), new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read orchestrator/approvers.json", e);
        }
    }
}
