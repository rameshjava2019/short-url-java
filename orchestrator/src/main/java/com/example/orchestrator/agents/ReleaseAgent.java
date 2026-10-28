package com.example.orchestrator.agents;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.orchestrator.agents.lib.Workspace;
import com.example.orchestrator.artifacts.Artifacts.ChangeSet;
import com.example.orchestrator.artifacts.Artifacts.FileChange;
import com.example.orchestrator.artifacts.Artifacts.ReleaseReport;
import com.example.orchestrator.core.Json;
import com.example.orchestrator.core.OrchestratorException;
import com.example.orchestrator.core.RunContext;

/**
 * Promotes the change set to the release target, then verifies it.
 *
 * Target: a sandbox copy of the project (default) or the project itself
 * ({@code --release-to project}). Every file it overwrites is backed up first; if
 * post-release verification fails, the backup is restored before failing.
 * {@link #rollback} restores the same backup if the run is re-planned later.
 */
public final class ReleaseAgent implements Agent {

    /** One promoted file and whether it existed before (so rollback knows to restore or delete it). */
    public record ManifestEntry(String file, boolean existed) {
    }

    public record Manifest(String target, List<ManifestEntry> files) {
    }

    @Override
    public String name() {
        return "release";
    }

    @Override
    public AgentResult run(AgentContext context) throws IOException {
        RunContext ctx = context.ctx();
        ChangeSet changes = ctx.get("changeSet", ChangeSet.class);
        boolean toProject = "project".equals(ctx.options().releaseTo);
        Path target = toProject ? ctx.projectRoot() : Workspace.create(ctx.projectRoot(), ctx.runDir().resolve("release-target"));
        Path backup = ctx.runDir().resolve("release-backup");
        Workspace.deleteRecursively(backup);

        List<ManifestEntry> manifest = new ArrayList<>();
        for (FileChange change : changes.files()) {
            Path destination = target.resolve(change.file());
            boolean existed = Files.exists(destination);
            if (existed) {
                Files.createDirectories(backup.resolve(change.file()).getParent());
                Files.copy(destination, backup.resolve(change.file()), StandardCopyOption.REPLACE_EXISTING);
            }
            manifest.add(new ManifestEntry(change.file(), existed));
            Files.createDirectories(destination.getParent());
            Files.copy(Path.of(changes.workspace()).resolve(change.file()), destination, StandardCopyOption.REPLACE_EXISTING);
        }
        Json.MAPPER.writeValue(manifestPath(ctx).toFile(), new Manifest(target.toString(), manifest));
        context.log("promoted " + manifest.size() + " file(s) to " + target);

        context.log("verifying the release target (mvn test)");
        TestAgent.SuiteResult verification = TestAgent.runSuite(target, context.signal());
        boolean drill = ctx.options().failReleaseVerification;
        if (verification.failed() > 0 || verification.total() == 0 || drill) {
            restore(context);
            String cause = drill ? "failure drill" : verification.failed() + " of " + verification.total() + " tests failed";
            throw new OrchestratorException.NonRetryable("Post-release verification failed (" + cause + "). Release target restored from backup.");
        }

        Map<String, Object> verified = new LinkedHashMap<>();
        verified.put("total", verification.total());
        verified.put("failed", verification.failed());
        List<Map<String, Object>> files = manifest.stream().map(m -> Map.<String, Object>of("file", m.file(), "existed", m.existed())).toList();
        return AgentResult.of("releaseReport", new ReleaseReport(target.toString(), toProject ? "project" : "sandbox", files, verified, Instant.now().toString()))
                .summary("released to " + target);
    }

    @Override
    public void rollback(AgentContext context) {
        restore(context);
    }

    private static void restore(AgentContext context) {
        Path manifestFile = manifestPath(context.ctx());
        if (!Files.exists(manifestFile)) return;
        try {
            Manifest manifest = Json.MAPPER.readValue(manifestFile.toFile(), Manifest.class);
            Path backup = context.ctx().runDir().resolve("release-backup");
            for (ManifestEntry entry : manifest.files()) {
                Path destination = Path.of(manifest.target()).resolve(entry.file());
                if (entry.existed()) Files.copy(backup.resolve(entry.file()), destination, StandardCopyOption.REPLACE_EXISTING);
                else Files.deleteIfExists(destination);
            }
            Files.delete(manifestFile);
            context.log("restored " + manifest.files().size() + " file(s) in " + manifest.target());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path manifestPath(RunContext ctx) {
        return ctx.runDir().resolve("release-manifest.json");
    }
}
