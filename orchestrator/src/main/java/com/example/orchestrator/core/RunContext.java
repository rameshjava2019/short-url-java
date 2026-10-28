package com.example.orchestrator.core;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.orchestrator.model.ArtifactRecord;
import com.example.orchestrator.model.Decision;
import com.example.orchestrator.model.Run;
import com.example.orchestrator.model.RunOptions;
import com.example.orchestrator.scenarios.Scenario;

/**
 * Cross-stage context for one run: versioned artifacts plus decision lineage.
 *
 * <ul>
 *   <li>An artifact is any stage output (spec, design, change set, reports ...).
 *       Putting identical content again does NOT create a new version, which is what
 *       lets the engine skip downstream work when an upstream re-run changes nothing.</li>
 *   <li>Every artifact version records which artifact versions it was derived from,
 *       so {@link #lineage(String, Integer)} can explain where any output came from.</li>
 *   <li>Decisions (by agents or humans) are recorded with their rationale and the
 *       exact inputs they were based on.</li>
 * </ul>
 *
 * Stages run in parallel threads, so every method locks on the run.
 */
public final class RunContext {

    public record PutResult(boolean changed, ArtifactRecord record) {
    }

    public record Lineage(String id, int version, String producedBy, String createdAt, List<String> decisions, List<Lineage> derivedFrom) {
    }

    private final Run run;
    private final RunStore store;
    private final Path projectRoot;
    private final Scenario scenario;
    private final Map<String, Object> cache = new LinkedHashMap<>();

    public RunContext(Run run, RunStore store, Path projectRoot, Scenario scenario) {
        this.run = run;
        this.store = store;
        this.projectRoot = projectRoot;
        this.scenario = scenario;
    }

    public Run run() {
        return run;
    }

    public Path runDir() {
        return store.runDir(run.id);
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public Scenario scenario() {
        return scenario;
    }

    public RunOptions options() {
        return run.options;
    }

    public ArtifactRecord ref(String id) {
        synchronized (run) {
            List<ArtifactRecord> versions = run.artifacts.get(id);
            return versions == null || versions.isEmpty() ? null : versions.get(versions.size() - 1);
        }
    }

    public boolean has(String id) {
        return ref(id) != null;
    }

    public String hashOf(String id) {
        ArtifactRecord ref = ref(id);
        return ref == null ? null : ref.hash;
    }

    /** Latest version of an artifact, converted to the given type, or null if it does not exist. */
    public <T> T get(String id, Class<T> type) {
        synchronized (run) {
            ArtifactRecord ref = ref(id);
            if (ref == null) return null;
            Object content = cache.computeIfAbsent(id + "@" + ref.version, key -> store.readArtifact(runDir().resolve(ref.path)));
            return Json.convert(content, type);
        }
    }

    /** Stores a new artifact version if the content changed. */
    public PutResult put(String id, Object content, String producedBy, List<String> derivedFrom) {
        synchronized (run) {
            Object normalized = Json.convert(content, Object.class);
            String hash = Json.hash(normalized);
            ArtifactRecord latest = ref(id);
            if (latest != null && latest.hash.equals(hash)) {
                return new PutResult(false, latest);
            }

            ArtifactRecord record = new ArtifactRecord();
            record.id = id;
            record.version = latest == null ? 1 : latest.version + 1;
            record.hash = hash;
            record.producedBy = producedBy;
            for (String dep : derivedFrom) {
                ArtifactRecord depRef = ref(dep);
                if (depRef != null) record.derivedFrom.add(new ArtifactRecord.Ref(dep, depRef.version));
            }
            Path file = store.writeArtifact(run.id, id, record.version, normalized);
            record.path = runDir().relativize(file).toString();
            record.createdAt = Instant.now().toString();
            run.artifacts.computeIfAbsent(id, key -> new ArrayList<>()).add(record);
            cache.put(id + "@" + record.version, normalized);
            return new PutResult(true, record);
        }
    }

    public Decision recordDecision(String stageId, String actor, String decision, String rationale, List<String> alternatives,
            List<String> inputs) {
        synchronized (run) {
            Decision entry = new Decision();
            entry.id = "D-" + (run.decisions.size() + 1);
            entry.at = Instant.now().toString();
            entry.stageId = stageId;
            entry.actor = actor;
            entry.decision = decision;
            entry.rationale = rationale;
            entry.alternatives = new ArrayList<>(alternatives);
            for (String id : inputs) {
                ArtifactRecord ref = ref(id);
                if (ref != null) entry.inputs.add(new ArtifactRecord.Ref(id, ref.version));
            }
            run.decisions.add(entry);
            return entry;
        }
    }

    public Run.Note noteFor(String stageId) {
        synchronized (run) {
            return run.notes.get(stageId);
        }
    }

    /** Walks derivedFrom links back to the original inputs. */
    public Lineage lineage(String id, Integer version) {
        synchronized (run) {
            List<ArtifactRecord> versions = run.artifacts.getOrDefault(id, List.of());
            ArtifactRecord record = versions.stream()
                    .filter(v -> version == null || v.version == version)
                    .reduce((first, second) -> version == null ? second : first)
                    .orElse(null);
            if (record == null) return null;
            List<String> decisions = run.decisions.stream()
                    .filter(d -> d.stageId.equals(record.producedBy))
                    .map(d -> d.id + ": " + d.decision)
                    .toList();
            List<Lineage> parents = record.derivedFrom.stream().map(dep -> lineage(dep.id(), dep.version())).toList();
            return new Lineage(record.id, record.version, record.producedBy, record.createdAt, decisions, parents);
        }
    }

    /** All artifact ids that currently exist. */
    public Set<String> artifactIds() {
        synchronized (run) {
            return Set.copyOf(run.artifacts.keySet());
        }
    }
}
