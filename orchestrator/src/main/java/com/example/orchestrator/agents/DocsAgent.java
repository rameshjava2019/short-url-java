package com.example.orchestrator.agents;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.orchestrator.agents.lib.Codebase;
import com.example.orchestrator.artifacts.Artifacts.ChangeSet;
import com.example.orchestrator.artifacts.Artifacts.Design;
import com.example.orchestrator.artifacts.Artifacts.DocExpectation;
import com.example.orchestrator.artifacts.Artifacts.DocsReport;
import com.example.orchestrator.artifacts.Artifacts.Line;

/**
 * Checks the documentation was updated with the change. Expectations come from
 * the design (what the plan author said the docs must mention) and from the code
 * itself: new environment variables in application.yml and new request mappings
 * in controllers must appear in the README.
 */
public final class DocsAgent implements Agent {

    private static final Pattern ENV_VAR = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]+)");
    private static final Pattern MAPPING = Pattern.compile("@(?:Get|Post|Put|Patch|Delete|Request)Mapping\\(\"([^\"{]+)");

    @Override
    public String name() {
        return "docs";
    }

    @Override
    public AgentResult run(AgentContext context) {
        ChangeSet changeSet = context.ctx().get("changeSet", ChangeSet.class);
        Design design = context.ctx().get("design", Design.class);
        Path workspace = Path.of(changeSet.workspace());

        List<DocExpectation> expectations = new ArrayList<>(design.docsExpectations());
        for (Line line : changeSet.addedLines()) {
            if (line.file().endsWith("application.yml")) {
                Matcher env = ENV_VAR.matcher(line.text());
                while (env.find()) expectations.add(new DocExpectation("README.md", env.group(1), "new environment variable in " + line.file(), null));
            }
            if (line.file().startsWith("src/main/java/")) {
                Matcher mapping = MAPPING.matcher(line.text());
                while (mapping.find()) expectations.add(new DocExpectation("README.md", mapping.group(1), "new endpoint in " + line.file(), null));
            }
        }

        List<DocExpectation> checked = expectations.stream().map(e -> {
            Path file = workspace.resolve(e.file());
            return e.withMet(Files.exists(file) && Codebase.read(file).contains(e.contains()));
        }).toList();
        List<DocExpectation> missing = checked.stream().filter(e -> !e.met()).toList();
        context.log((checked.size() - missing.size()) + "/" + checked.size() + " documentation expectations met");
        return AgentResult.of("docsReport", new DocsReport(checked, missing)).summary(missing.size() + " missing");
    }
}
