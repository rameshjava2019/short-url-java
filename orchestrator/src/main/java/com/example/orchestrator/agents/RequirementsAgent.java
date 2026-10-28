package com.example.orchestrator.agents;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.orchestrator.artifacts.Artifacts.Ambiguity;
import com.example.orchestrator.artifacts.Artifacts.Criterion;
import com.example.orchestrator.artifacts.Artifacts.RequirementSource;
import com.example.orchestrator.artifacts.Artifacts.Spec;
import com.example.orchestrator.model.Run;

/**
 * Rule-based requirement analysis (deterministic, offline).
 *
 * Reads the requirement document and produces a normalized spec:
 * <ul>
 *   <li>acceptance criteria from an "Acceptance criteria" section, or derived from
 *       "should/must" sentences when there is none</li>
 *   <li>ambiguities: vague wording (from the knowledge base) with no measurable
 *       criteria, plus anything listed under "Open questions"</li>
 *   <li>keywords the design agent uses for impact analysis</li>
 * </ul>
 */
public final class RequirementsAgent implements Agent {

    private static final Set<String> STOPWORDS = Set.of(("the a an and or of to for in on with is are be by as it this that should must when then "
            + "can not no new its from at all each any their they them we our").split(" "));
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*]\\s+(.+)$");

    @Override
    public String name() {
        return "requirements";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String text = context.ctx().get("requirementSource", RequirementSource.class).text();
        Spec spec = analyze(text);
        Run.Note note = context.ctx().noteFor("requirements");
        if (note != null) spec = withNote(spec, note.note);

        boolean derived = spec.acceptanceCriteria().stream().anyMatch(Criterion::derived);
        return AgentResult.of("spec", spec)
                .approvalOptions(spec.ambiguities().isEmpty() ? null : spec.ambiguities().get(0).options())
                .decision("Normalized requirement into " + spec.acceptanceCriteria().size() + " acceptance criteria and "
                                + spec.ambiguities().size() + " open question(s)",
                        derived ? "No explicit acceptance criteria; derived them from \"should/must\" sentences"
                                : "Used the explicit \"Acceptance criteria\" section")
                .summary(spec.acceptanceCriteria().size() + " criteria, " + spec.ambiguities().size() + " ambiguities");
    }

    public static Spec analyze(String text) {
        Map<String, String> sections = splitSections(text);
        Matcher titleMatch = Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE).matcher(text);
        String title = titleMatch.find() ? titleMatch.group(1).trim() : "Untitled requirement";
        String summary = Arrays.stream(sections.getOrDefault("", "").split("\\n\\s*\\n"))
                .map(String::trim).filter(p -> !p.isEmpty() && !p.startsWith("#")).findFirst().orElse("");

        List<Criterion> criteria = new ArrayList<>();
        List<String> bullets = bullets(findSection(sections, "acceptance criteria"));
        for (int i = 0; i < bullets.size(); i++) criteria.add(new Criterion("AC-" + (i + 1), bullets.get(i), false, false));

        if (criteria.isEmpty()) {
            List<String> sentences = Arrays.stream(text.replaceAll("(?m)^#.*$", "").split("(?<=[.!?])\\s+"))
                    .map(String::trim).filter(s -> s.matches("(?is).*\\b(should|must)\\b.*")).toList();
            for (int i = 0; i < sentences.size(); i++) criteria.add(new Criterion("AC-" + (i + 1), sentences.get(i), true, false));
        }

        boolean measurable = criteria.stream().anyMatch(c -> !c.derived());
        List<Ambiguity> ambiguities = new ArrayList<>();
        if (!measurable) {
            for (Interpretations.Interpretation k : Interpretations.ALL) {
                if (k.pattern().matcher(text).find()) ambiguities.add(new Ambiguity(k.term(), k.question(), k.options()));
            }
        }
        for (String question : bullets(findSection(sections, "open questions"))) {
            ambiguities.add(new Ambiguity("open question", question, List.of()));
        }

        String keywordSource = title + " " + String.join(" ", criteria.stream().map(Criterion::text).toList());
        return new Spec(title, summary, criteria, ambiguities, keywords(keywordSource), "rule-based", null);
    }

    static Spec withNote(Spec spec, String note) {
        return new Spec(spec.title(), spec.summary(), spec.acceptanceCriteria(), spec.ambiguities(), spec.keywords(), spec.analyzer(), note);
    }

    private static Map<String, String> splitSections(String text) {
        Map<String, String> sections = new LinkedHashMap<>();
        String current = "";
        sections.put(current, "");
        for (String line : text.split("\n")) {
            Matcher heading = Pattern.compile("^##\\s+(.+)$").matcher(line);
            if (heading.matches()) {
                current = heading.group(1).trim();
                sections.put(current, "");
            } else {
                sections.merge(current, line + "\n", String::concat);
            }
        }
        return sections;
    }

    private static String findSection(Map<String, String> sections, String name) {
        return sections.entrySet().stream().filter(e -> e.getKey().toLowerCase().contains(name)).map(Map.Entry::getValue).findFirst().orElse("");
    }

    private static List<String> bullets(String sectionText) {
        return Arrays.stream(sectionText.split("\n")).map(BULLET::matcher).filter(Matcher::matches).map(m -> m.group(1).trim()).toList();
    }

    private static List<String> keywords(String text) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String word : text.toLowerCase().replaceAll("[`'\"]", "").split("[^a-z0-9/]+")) {
            if (word.length() >= 4 && !STOPWORDS.contains(word)) counts.merge(word, 1, Integer::sum);
        }
        return counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(12).map(Map.Entry::getKey).toList();
    }
}
