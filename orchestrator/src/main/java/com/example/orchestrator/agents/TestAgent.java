package com.example.orchestrator.agents;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.example.orchestrator.agents.lib.Codebase;
import com.example.orchestrator.agents.lib.Exec;
import com.example.orchestrator.artifacts.Artifacts.ChangeSet;
import com.example.orchestrator.artifacts.Artifacts.TestReport;
import com.example.orchestrator.core.OrchestratorException;
import com.example.orchestrator.core.StopSignal;

/**
 * Runs the full test suite (unit + MockMvc tests against an embedded MongoDB)
 * inside the workspace with Maven, and reads the Surefire XML reports.
 */
public final class TestAgent implements Agent {

    @Override
    public String name() {
        return "test";
    }

    @Override
    public AgentResult run(AgentContext context) {
        Path workspace = Path.of(context.ctx().get("changeSet", ChangeSet.class).workspace());
        context.log("running mvn test in the workspace");
        SuiteResult suite = runSuite(workspace, context.signal());
        TestReport report = new TestReport(suite.total(), suite.total() - suite.failed() - suite.skipped(), suite.failed(), suite.skipped(),
                suite.durationMs(), suite.failures(), Codebase.countTests(context.ctx().projectRoot()), Codebase.countTests(workspace),
                suite.outputTail());
        context.log(report.passed() + "/" + report.total() + " passed (" + report.durationMs() / 1000 + " s)");
        return AgentResult.of("testReport", report).summary(report.passed() + "/" + report.total() + " passed");
    }

    public record SuiteResult(int total, int failed, int skipped, long durationMs, List<String> failures, String outputTail) {
    }

    /** Runs {@code mvn test} and sums the Surefire reports. Also used by the release agent. */
    public static SuiteResult runSuite(Path projectDir, StopSignal signal) {
        long started = System.currentTimeMillis();
        Exec.Result result = Exec.maven(projectDir, Duration.ofMinutes(9), signal, "test");
        if (result.killed()) throw new OrchestratorException("Test run was stopped or timed out");

        int total = 0;
        int failed = 0;
        int skipped = 0;
        List<String> failures = new ArrayList<>();
        Path reports = projectDir.resolve("target/surefire-reports");
        try (var files = Files.exists(reports) ? Files.list(reports) : java.util.stream.Stream.<Path>empty()) {
            for (Path file : files.filter(f -> f.getFileName().toString().matches("TEST-.*\\.xml")).toList()) {
                Document xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile());
                Element suite = xml.getDocumentElement();
                total += Integer.parseInt(suite.getAttribute("tests"));
                failed += Integer.parseInt(suite.getAttribute("failures")) + Integer.parseInt(suite.getAttribute("errors"));
                skipped += Integer.parseInt(suite.getAttribute("skipped"));
                NodeList cases = suite.getElementsByTagName("testcase");
                for (int i = 0; i < cases.getLength(); i++) {
                    Element testCase = (Element) cases.item(i);
                    if (testCase.getElementsByTagName("failure").getLength() + testCase.getElementsByTagName("error").getLength() > 0) {
                        failures.add(testCase.getAttribute("classname") + "." + testCase.getAttribute("name"));
                    }
                }
            }
        } catch (Exception e) {
            throw new OrchestratorException("Could not read test reports: " + e.getMessage());
        }
        if (total == 0 && result.exitCode() != 0) {
            throw new OrchestratorException.NonRetryable("Maven test run failed before running tests: " + Exec.tail(result.output(), 8));
        }
        return new SuiteResult(total, failed, skipped, System.currentTimeMillis() - started, failures, Exec.tail(result.output(), 30));
    }
}
