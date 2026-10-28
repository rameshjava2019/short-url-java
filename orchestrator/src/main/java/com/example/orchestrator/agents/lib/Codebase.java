package com.example.orchestrator.agents.lib;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.orchestrator.artifacts.Artifacts.RelatedFile;

/** Codebase reasoning helpers for a Java / Maven project. */
public final class Codebase {

    private static final Pattern TEST_ANNOTATION = Pattern.compile("^\\s*@(Test|ParameterizedTest)\\b", Pattern.MULTILINE);
    private static final Pattern TEXT_FILE = Pattern.compile(".*\\.(java|html|css|js|yml|yaml|properties|xml|md)$");

    private Codebase() {
    }

    /** Text source files under src/ (binary files such as .DS_Store are skipped). */
    public static List<String> sourceFiles(Path root) {
        return Workspace.listFiles(root.resolve("src")).stream().filter(f -> TEXT_FILE.matcher(f).matches()).map(f -> "src/" + f).toList();
    }

    /**
     * Java files that use the given class: they import it, or they are in the same
     * package and mention it by name. This is the blast radius of changing it.
     */
    public static List<String> findDependents(Path root, String relativeFile) {
        if (!relativeFile.endsWith(".java")) return List.of();
        String className = Path.of(relativeFile).getFileName().toString().replace(".java", "");
        String packageDir = Path.of(relativeFile).getParent().toString().replace('\\', '/').replaceFirst("^src/(main|test)/java/", "");
        String fullName = packageDir.replace('/', '.') + "." + className;
        Pattern mention = Pattern.compile("\\b" + Pattern.quote(className) + "\\b");

        return sourceFiles(root).stream()
                .filter(file -> file.endsWith(".java") && !file.equals(relativeFile))
                .filter(file -> {
                    String text = read(root.resolve(file));
                    boolean samePackage = file.replaceFirst("^src/(main|test)/java/", "").startsWith(packageDir + "/")
                            && !file.replaceFirst("^src/(main|test)/java/", "").substring(packageDir.length() + 1).contains("/");
                    return text.contains("import " + fullName + ";") || (samePackage && mention.matcher(text).find());
                })
                .toList();
    }

    /** Files mentioning any of the keywords, ranked by number of hits. */
    public static List<RelatedFile> searchKeywords(Path root, List<String> keywords, int limit) {
        List<Pattern> patterns = keywords.stream().filter(k -> k.length() >= 4)
                .map(k -> Pattern.compile(Pattern.quote(k), Pattern.CASE_INSENSITIVE)).toList();
        return sourceFiles(root).stream()
                .map(file -> {
                    String text = read(root.resolve(file));
                    int hits = 0;
                    for (Pattern pattern : patterns) {
                        Matcher matcher = pattern.matcher(text);
                        while (matcher.find()) hits++;
                    }
                    return new RelatedFile(file, hits);
                })
                .filter(r -> r.hits() > 0)
                .sorted(Comparator.comparingInt(RelatedFile::hits).reversed())
                .limit(limit)
                .toList();
    }

    /** Static count of test methods under src/test, to detect deleted tests. */
    public static int countTests(Path root) {
        return sourceFiles(root).stream()
                .filter(file -> file.startsWith("src/test/") && file.endsWith(".java"))
                .mapToInt(file -> (int) TEST_ANNOTATION.matcher(read(root.resolve(file))).results().count())
                .sum();
    }

    /** Reads a file as UTF-8, replacing any invalid bytes instead of failing. */
    public static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
