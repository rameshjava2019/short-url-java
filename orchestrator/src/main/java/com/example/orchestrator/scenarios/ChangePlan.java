package com.example.orchestrator.scenarios;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

import com.example.orchestrator.artifacts.Artifacts.Clarification;
import com.example.orchestrator.artifacts.Artifacts.DocExpectation;
import com.example.orchestrator.artifacts.Artifacts.Spec;

/**
 * A change plan written and reviewed by engineers. Agents select it, apply it in
 * an isolated workspace, verify it and document it; they never invent code.
 */
public record ChangePlan(
        String id,
        String title,
        String approach,
        BiPredicate<Spec, Clarification> matches,
        List<String> alternativesConsidered,
        List<Task> tasks,
        List<String> refinedCriteria,
        List<String> risks,
        List<String> assumptions,
        String rollbackPlan,
        List<DocExpectation> docsExpectations,
        List<Operation> operations) {

    /** A unit of work; it covers every acceptance criterion whose text contains one of its keywords. */
    public record Task(String id, String title, List<String> keywords, List<String> dependsOn) {
    }

    /** A file change: "create" (new file) or "replace" (the find text must occur exactly once). */
    public record Operation(String type, String file, String find, String text) {
    }

    public static Builder builder(String id, String title) {
        return new Builder(id, title);
    }

    public static final class Builder {
        private final String id;
        private final String title;
        private String approach = "";
        private BiPredicate<Spec, Clarification> matches = (spec, clarification) -> true;
        private final List<String> alternatives = new ArrayList<>();
        private final List<Task> tasks = new ArrayList<>();
        private final List<String> refinedCriteria = new ArrayList<>();
        private final List<String> risks = new ArrayList<>();
        private final List<String> assumptions = new ArrayList<>();
        private String rollbackPlan = "";
        private final List<DocExpectation> docs = new ArrayList<>();
        private final List<Operation> operations = new ArrayList<>();

        private Builder(String id, String title) {
            this.id = id;
            this.title = title;
        }

        public Builder approach(String text) {
            this.approach = text;
            return this;
        }

        public Builder matches(BiPredicate<Spec, Clarification> predicate) {
            this.matches = predicate;
            return this;
        }

        public Builder alternative(String text) {
            alternatives.add(text);
            return this;
        }

        public Builder task(String taskId, String taskTitle, List<String> keywords, String... dependsOn) {
            tasks.add(new Task(taskId, taskTitle, keywords, List.of(dependsOn)));
            return this;
        }

        public Builder refinedCriterion(String text) {
            refinedCriteria.add(text);
            return this;
        }

        public Builder risk(String text) {
            risks.add(text);
            return this;
        }

        public Builder assumption(String text) {
            assumptions.add(text);
            return this;
        }

        public Builder rollbackPlan(String text) {
            this.rollbackPlan = text;
            return this;
        }

        public Builder docsMustMention(String file, String text, String why) {
            docs.add(new DocExpectation(file, text, why, null));
            return this;
        }

        public Builder create(String file, String content) {
            operations.add(new Operation("create", file, null, content));
            return this;
        }

        public Builder replace(String file, String find, String replacement) {
            operations.add(new Operation("replace", file, find, replacement));
            return this;
        }

        public ChangePlan build() {
            return new ChangePlan(id, title, approach, matches, List.copyOf(alternatives), List.copyOf(tasks), List.copyOf(refinedCriteria),
                    List.copyOf(risks), List.copyOf(assumptions), rollbackPlan, List.copyOf(docs), List.copyOf(operations));
        }
    }
}
