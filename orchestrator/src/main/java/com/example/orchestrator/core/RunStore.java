package com.example.orchestrator.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import com.example.orchestrator.model.Run;

/**
 * Persists runs on disk so execution is stateful and resumable:
 *
 * <pre>
 *   &lt;root&gt;/runs/&lt;runId&gt;/state.json     current run state (rewritten atomically)
 *   &lt;root&gt;/runs/&lt;runId&gt;/audit.jsonl    hash-chained audit trail (append-only)
 *   &lt;root&gt;/runs/&lt;runId&gt;/artifacts/     versioned stage outputs
 *   &lt;root&gt;/runs/&lt;runId&gt;/STOP           present when a safe-stop was requested
 *   &lt;root&gt;/runs/&lt;runId&gt;/.lock          held by the process executing the run
 * </pre>
 */
public final class RunStore {

    public record StopRequest(String by, String reason, String at) {
    }

    private final Path runsDir;

    public RunStore(Path rootDir) {
        this.runsDir = rootDir.resolve("runs");
        uncheck(() -> Files.createDirectories(runsDir));
    }

    public String newRunId() {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        byte[] random = new byte[2];
        new SecureRandom().nextBytes(random);
        return "run-" + stamp + "-" + HexFormat.of().formatHex(random);
    }

    public Path runDir(String runId) {
        if (!runId.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid run id \"" + runId + "\"");
        return runsDir.resolve(runId);
    }

    public Path auditPath(String runId) {
        return runDir(runId).resolve("audit.jsonl");
    }

    public boolean exists(String runId) {
        return Files.exists(runDir(runId).resolve("state.json"));
    }

    public void create(Run run) {
        uncheck(() -> Files.createDirectories(runDir(run.id).resolve("artifacts")));
        save(run);
    }

    public Run load(String runId) {
        if (!exists(runId)) throw new IllegalArgumentException("Run \"" + runId + "\" not found");
        return uncheck(() -> Json.MAPPER.readValue(runDir(runId).resolve("state.json").toFile(), Run.class));
    }

    /** Writes to a temp file and renames, so a crash never leaves half-written state. */
    public void save(Run run) {
        Path file = runDir(run.id).resolve("state.json");
        Path temp = file.resolveSibling("state.json." + ProcessHandle.current().pid() + ".tmp");
        uncheck(() -> {
            Json.MAPPER.writeValue(temp.toFile(), run);
            return Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        });
    }

    public List<Run> list() {
        try (Stream<Path> dirs = Files.list(runsDir)) {
            return dirs.map(dir -> dir.getFileName().toString()).filter(this::exists).map(this::load)
                    .sorted(Comparator.comparing(run -> run.createdAt)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path writeArtifact(String runId, String artifactId, int version, Object content) {
        String safeId = artifactId.replaceAll("[^A-Za-z0-9_.-]", "_");
        Path file = runDir(runId).resolve("artifacts").resolve(safeId + ".v" + version + ".json");
        uncheck(() -> {
            Json.MAPPER.writeValue(file.toFile(), content);
            return null;
        });
        return file;
    }

    public Object readArtifact(Path file) {
        return uncheck(() -> Json.MAPPER.readValue(file.toFile(), Object.class));
    }

    // ---- Safe-stop signalling (works across processes) ----

    public void requestStop(String runId, String by, String reason) {
        uncheck(() -> {
            Json.MAPPER.writeValue(runDir(runId).resolve("STOP").toFile(), new StopRequest(by, reason, java.time.Instant.now().toString()));
            return null;
        });
    }

    public StopRequest readStop(String runId) {
        Path file = runDir(runId).resolve("STOP");
        return Files.exists(file) ? uncheck(() -> Json.MAPPER.readValue(file.toFile(), StopRequest.class)) : null;
    }

    public void clearStop(String runId) {
        uncheck(() -> Files.deleteIfExists(runDir(runId).resolve("STOP")));
    }

    // ---- Single-writer lock: two processes must never drive the same run ----

    public void acquireLock(String runId) {
        Path file = runDir(runId).resolve(".lock");
        long me = ProcessHandle.current().pid();
        try {
            Files.writeString(file, String.valueOf(me), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            long owner = Long.parseLong(uncheck(() -> Files.readString(file)).trim());
            boolean ownerAlive = owner != me && ProcessHandle.of(owner).map(ProcessHandle::isAlive).orElse(false);
            if (ownerAlive) {
                throw new IllegalStateException("Run \"" + runId + "\" is already being executed by process " + owner);
            }
            uncheck(() -> Files.writeString(file, String.valueOf(me))); // stale lock from a crashed process
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void releaseLock(String runId) {
        uncheck(() -> Files.deleteIfExists(runDir(runId).resolve(".lock")));
    }

    @FunctionalInterface
    interface IoAction<T> {
        T run() throws IOException;
    }

    static <T> T uncheck(IoAction<T> action) {
        try {
            return action.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
