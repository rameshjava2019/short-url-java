package com.example.orchestrator.agents;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.orchestrator.artifacts.Artifacts.ChangeSet;
import com.example.orchestrator.artifacts.Artifacts.CompatibilityReport;
import com.example.orchestrator.artifacts.Artifacts.Line;

/**
 * Data compatibility check, added to the plan when a MongoDB document class
 * changes. Existing documents must keep working without a migration:
 * <ul>
 *   <li>no existing field may be removed or renamed</li>
 *   <li>a new unique index needs {@code sparse = true}, otherwise every old document
 *       has a missing (null) value and the index cannot be built</li>
 * </ul>
 */
public final class CompatibilityAgent implements Agent {

    private static final Pattern FIELD = Pattern.compile("^\\s*private\\s+([\\w<>, ]+)\\s+(\\w+)\\s*;");
    private static final Pattern PRIMITIVE = Pattern.compile("^(int|long|boolean|double|float|short|byte|char)$");

    @Override
    public String name() {
        return "compatibility";
    }

    @Override
    public AgentResult run(AgentContext context) {
        ChangeSet changeSet = context.ctx().get("changeSet", ChangeSet.class);
        List<Line> added = changeSet.addedLines().stream().filter(l -> l.file().contains("/model/")).toList();
        List<Line> removed = changeSet.removedLines().stream().filter(l -> l.file().contains("/model/")).toList();
        List<String> issues = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<String> newFields = new ArrayList<>();

        for (Line line : removed) {
            Matcher field = FIELD.matcher(line.text());
            if (field.find()) issues.add("Field removed or renamed: " + field.group(2) + " (existing documents still have it)");
        }
        for (int i = 0; i < added.size(); i++) {
            Matcher field = FIELD.matcher(added.get(i).text());
            if (!field.find()) continue;
            newFields.add(field.group(2));
            String previous = i > 0 ? added.get(i - 1).text() : "";
            if (previous.contains("@Indexed(unique = true") && !previous.contains("sparse = true")) {
                issues.add("New unique index on " + field.group(2) + " without sparse = true: existing documents all lack the field");
            }
            if (PRIMITIVE.matcher(field.group(1).trim()).matches()) {
                notes.add(field.group(2) + " is a primitive; existing documents will read it as its default (0 / false)");
            } else {
                notes.add(field.group(2) + " is nullable; existing documents will read it as null");
            }
        }

        context.log("new fields: " + (newFields.isEmpty() ? "none" : String.join(", ", newFields)) + "; " + issues.size() + " issue(s)");
        return AgentResult.of("compatibilityReport", new CompatibilityReport(issues.isEmpty(), issues, newFields, notes))
                .summary(issues.isEmpty() ? "backward compatible" : issues.size() + " issue(s)");
    }
}
