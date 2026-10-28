package com.example.orchestrator.agents;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import com.example.orchestrator.agents.lib.Diff;
import com.example.orchestrator.agents.lib.Exec;
import com.example.orchestrator.agents.lib.Workspace;
import com.example.orchestrator.artifacts.Artifacts.ChangeSet;
import com.example.orchestrator.artifacts.Artifacts.Design;
import com.example.orchestrator.artifacts.Artifacts.FileChange;
import com.example.orchestrator.artifacts.Artifacts.Line;
import com.example.orchestrator.artifacts.Artifacts.Totals;
import com.example.orchestrator.core.OrchestratorException;
import com.example.orchestrator.policies.WriteGuard;
import com.example.orchestrator.scenarios.ChangePlan;

/**
 * Applies the reviewed change plan inside an isolated workspace (a copy of the
 * project under the run folder). Every write passes the write guard. Then it
 * compiles main and test code with Maven and produces a change set with per-file
 * diffs and a patch file for human review. Rollback deletes the workspace.
 */
public final class ImplementationAgent implements Agent {

    @Override
    public String name() {
        return "implementation";
    }

    @Override
    public AgentResult run(AgentContext context) {
        var ctx = context.ctx();
        Design design = ctx.get("design", Design.class);
        ChangePlan plan = ctx.scenario().changePlans().stream().filter(p -> p.id().equals(design.planId())).findFirst().orElseThrow();
        Path workspace = workspaceDir(context);

        Workspace.create(ctx.projectRoot(), workspace);
        context.log("workspace created at " + ctx.projectRoot().relativize(workspace));

        for (ChangePlan.Operation op : plan.operations()) {
            context.signal().throwIfStopped();
            apply(workspace, WriteGuard.assertWriteAllowed(op.file()), op);
        }

        List<FileChange> files = new ArrayList<>();
        List<Line> added = new ArrayList<>();
        List<Line> removed = new ArrayList<>();
        StringBuilder patch = new StringBuilder();
        for (String file : new LinkedHashSet<>(plan.operations().stream().map(ChangePlan.Operation::file).toList())) {
            Path original = ctx.projectRoot().resolve(file);
            boolean existed = Files.exists(original);
            Diff.Result diff = Diff.unified(file, existed ? original : Path.of("/dev/null"), workspace.resolve(file));
            patch.append(diff.patch()).append('\n');
            diff.added().forEach(text -> added.add(new Line(file, text)));
            diff.removed().forEach(text -> removed.add(new Line(file, text)));
            files.add(new FileChange(file, existed ? "modify" : "create", diff.added().size(), diff.removed().size()));
        }

        context.log("compiling main and test code (mvn test-compile)");
        Exec.Result compile = Exec.maven(workspace, Duration.ofMinutes(4), context.signal(), "test-compile");
        context.signal().throwIfStopped();
        List<String> compileErrors = compile.exitCode() == 0 ? List.of()
                : compile.output().lines().filter(l -> l.startsWith("[ERROR]") && l.contains(".java")).limit(10)
                        .map(l -> l.replace(workspace.toString() + "/", "")).toList();
        if (compile.exitCode() != 0 && compileErrors.isEmpty()) compileErrors = List.of(Exec.tail(compile.output(), 5));

        Path patchFile = ctx.runDir().resolve("changes.patch");
        write(patchFile, patch.toString());
        context.log("applied " + plan.operations().size() + " operations to " + files.size() + " files; patch at "
                + ctx.projectRoot().relativize(patchFile));

        int totalAdded = files.stream().mapToInt(FileChange::added).sum();
        int totalRemoved = files.stream().mapToInt(FileChange::removed).sum();
        ChangeSet changeSet = new ChangeSet(workspace.toString(), plan.id(), files, added, removed,
                new Totals(files.size(), totalAdded, totalRemoved), patchFile.toString(), compileErrors);
        return AgentResult.of("changeSet", changeSet)
                .decision("Applied change plan \"" + plan.id() + "\" in an isolated workspace",
                        "The project itself is only changed by the release stage, after approval")
                .summary(files.size() + " files, +" + totalAdded + " / -" + totalRemoved);
    }

    @Override
    public void rollback(AgentContext context) {
        Workspace.deleteRecursively(workspaceDir(context));
        context.log("workspace discarded");
    }

    private static void apply(Path workspace, String relative, ChangePlan.Operation op) {
        Path target = workspace.resolve(relative);
        switch (op.type()) {
            case "create" -> {
                if (Files.exists(target)) {
                    throw new OrchestratorException.NonRetryable("Cannot create " + relative + ": it already exists (has this change already been released?)");
                }
                write(target, op.text());
            }
            case "replace" -> {
                if (!Files.exists(target)) throw new OrchestratorException.NonRetryable("Cannot modify " + relative + ": file not found");
                String text = read(target);
                int occurrences = text.split(java.util.regex.Pattern.quote(op.find()), -1).length - 1;
                if (occurrences != 1) {
                    throw new OrchestratorException.NonRetryable("Expected the anchor text exactly once in " + relative + ", found " + occurrences
                            + ". The code changed since the plan was written (or the change was already released); the plan needs review.");
                }
                write(target, text.replace(op.find(), op.text()));
            }
            default -> throw new OrchestratorException.NonRetryable("Unknown operation type \"" + op.type() + "\"");
        }
    }

    private static Path workspaceDir(AgentContext context) {
        return context.ctx().runDir().resolve("workspace");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
