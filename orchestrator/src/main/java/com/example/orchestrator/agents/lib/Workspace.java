package com.example.orchestrator.agents.lib;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Isolated copies of the project. Agents only ever change a copy; the real
 * project is touched by the release stage, after human approval.
 */
public final class Workspace {

    // Never copied: build output, run data, the orchestrator itself, tooling folders.
    private static final Set<String> EXCLUDED = Set.of("target", ".orchestrator", "orchestrator", ".git", ".idea", ".claude", ".vscode");

    private Workspace() {
    }

    public static Path create(Path sourceDir, Path workspaceDir) {
        deleteRecursively(workspaceDir);
        try {
            Files.createDirectories(workspaceDir);
            Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(sourceDir) && sourceDir.relativize(dir).getNameCount() == 1 && EXCLUDED.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Files.createDirectories(workspaceDir.resolve(sourceDir.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.copy(file, workspaceDir.resolve(sourceDir.relativize(file).toString()),
                            StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return workspaceDir;
    }

    /** Files under {@code dir} (relative, with "/" separators), skipping excluded folders. */
    public static List<String> listFiles(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> paths = Files.walk(dir)) {
            List<String> files = new ArrayList<>();
            paths.filter(Files::isRegularFile).forEach(path -> {
                String relative = dir.relativize(path).toString().replace('\\', '/');
                if (!EXCLUDED.contains(relative.split("/")[0])) files.add(relative);
            });
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
