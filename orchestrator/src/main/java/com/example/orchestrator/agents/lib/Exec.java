package com.example.orchestrator.agents.lib;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.example.orchestrator.core.OrchestratorException;
import com.example.orchestrator.core.StopSignal;

/**
 * Runs a command and collects its output. The process is killed when the stop
 * signal fires (safe-stop / stage timeout) or the timeout passes.
 */
public final class Exec {

    public record Result(int exitCode, String output, boolean killed) {
    }

    private Exec() {
    }

    public static Result run(Path workingDir, List<String> command, Duration timeout, StopSignal signal) {
        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDir.toFile()).redirectErrorStream(true);
        // Child Maven builds use the same JDK as the orchestrator.
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new OrchestratorException("Could not start " + command.get(0) + ": " + e.getMessage());
        }
        if (signal != null) signal.onStop(process::destroyForcibly);

        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });

        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) process.destroyForcibly().waitFor();
            boolean killed = !finished || (signal != null && signal.isStopped());
            return new Result(finished ? process.exitValue() : -1, output.get(10, TimeUnit.SECONDS), killed);
        } catch (Exception e) {
            process.destroyForcibly();
            throw new OrchestratorException.Aborted();
        }
    }

    /** Runs the project's Maven Wrapper in batch, quiet mode. */
    public static Result maven(Path projectDir, Duration timeout, StopSignal signal, String... goals) {
        List<String> command = new java.util.ArrayList<>(List.of("./mvnw", "-B", "-q"));
        command.addAll(List.of(goals));
        return run(projectDir, command, timeout, signal);
    }

    /** The last {@code lines} lines of a command's output. */
    public static String tail(String output, int lines) {
        String[] all = output.split("\n");
        return String.join("\n", java.util.Arrays.copyOfRange(all, Math.max(0, all.length - lines), all.length));
    }
}
