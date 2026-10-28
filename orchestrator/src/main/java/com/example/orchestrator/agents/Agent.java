package com.example.orchestrator.agents;

/**
 * An agent does the work of a stage. It reads artifacts from the context and
 * returns new artifacts, decisions and (optionally) plan changes. The engine
 * handles retries, gates, policies and approvals around it.
 */
public interface Agent {

    String name();

    AgentResult run(AgentContext context) throws Exception;

    /** Undo the agent's side effects (only for stages marked sideEffects). */
    default void rollback(AgentContext context) throws Exception {
    }
}
