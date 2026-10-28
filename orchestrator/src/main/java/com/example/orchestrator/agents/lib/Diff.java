package com.example.orchestrator.agents.lib;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/** Unified diff of one file, using the system {@code diff}. */
public final class Diff {

    public record Result(String patch, List<String> added, List<String> removed) {
    }

    private Diff() {
    }

    /** {@code before} may be /dev/null for a newly created file. */
    public static Result unified(String relativeFile, Path before, Path after) {
        Exec.Result result = Exec.run(after.getParent(), List.of("diff", "-u", "--label", "a/" + relativeFile, "--label", "b/" + relativeFile,
                before.toString(), after.toString()), Duration.ofSeconds(30), null);
        List<String> lines = Arrays.asList(result.output().split("\n"));
        List<String> added = lines.stream().filter(l -> l.startsWith("+") && !l.startsWith("+++")).map(l -> l.substring(1)).toList();
        List<String> removed = lines.stream().filter(l -> l.startsWith("-") && !l.startsWith("---")).map(l -> l.substring(1)).toList();
        return new Result(result.output(), added, removed);
    }
}
