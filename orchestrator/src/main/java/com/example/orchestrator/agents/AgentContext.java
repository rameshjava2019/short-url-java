package com.example.orchestrator.agents;

import java.util.Map;
import java.util.function.BiConsumer;

import com.example.orchestrator.core.RunContext;
import com.example.orchestrator.core.StopSignal;
import com.example.orchestrator.workflow.Stage;

/** What an agent gets: the run context, its stage, a stop signal and a logger that writes to the audit trail. */
public record AgentContext(RunContext ctx, Stage stage, StopSignal signal, BiConsumer<String, Map<String, Object>> logger) {

    public void log(String message) {
        logger.accept(message, Map.of());
    }
}
