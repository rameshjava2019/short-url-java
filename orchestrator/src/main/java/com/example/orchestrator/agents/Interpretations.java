package com.example.orchestrator.agents;

import java.util.List;
import java.util.regex.Pattern;

import com.example.orchestrator.model.Approval.Option;

/**
 * Domain knowledge the rule-based requirements agent uses to spot vague wording
 * and propose concrete interpretations for a human to choose from. (The Claude
 * agent proposes its own, but is asked to reuse these ids.)
 */
public final class Interpretations {

    public record Interpretation(String term, Pattern pattern, String question, List<Option> options) {
    }

    public static final List<Interpretation> ALL = List.of(
            new Interpretation("safer", Pattern.compile("\\b(safer|safe|more secure|security)\\b", Pattern.CASE_INSENSITIVE),
                    "What does \"safer\" mean for people who click short links?",
                    List.of(new Option("domain-blocklist", "Refuse to shorten URLs on a configurable list of blocked domains"),
                            new Option("interstitial-warning", "Show a preview / warning page before redirecting"),
                            new Option("link-expiry", "Let links expire so old links stop working"))),
            new Interpretation("faster", Pattern.compile("\\b(faster|quicker|performance|speed up)\\b", Pattern.CASE_INSENSITIVE),
                    "Which operation should be faster, and by how much?",
                    List.of(new Option("redirect-cache", "Cache code → URL lookups in memory to speed up redirects"),
                            new Option("async-click-count", "Count clicks asynchronously so redirects never wait on a write"))),
            new Interpretation("better", Pattern.compile("\\b(better|improve|nicer|user[- ]friendly)\\b", Pattern.CASE_INSENSITIVE),
                    "What specific outcome would make it \"better\"?", List.of()));

    private Interpretations() {
    }
}
