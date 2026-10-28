package com.example.orchestrator.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** How a run was started (CLI flags). */
public class RunOptions {

    /** Use Claude for requirement analysis (falls back to the rule-based agent). */
    public boolean useLlm;
    /** "sandbox" (default): release into a copy of the project. "project": release into the project itself. */
    public String releaseTo = "sandbox";
    /** Failure drill: fail the first N attempts of a stage. */
    public Map<String, Integer> injectFailures = new LinkedHashMap<>();
    /** Failure drill: make post-release verification fail. */
    public boolean failReleaseVerification;
    /** Roll back side effects when the run fails (default true). */
    public boolean rollback = true;
}
