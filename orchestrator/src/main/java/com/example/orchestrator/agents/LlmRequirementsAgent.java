package com.example.orchestrator.agents;

import java.util.List;
import java.util.Optional;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.example.orchestrator.artifacts.Artifacts.Ambiguity;
import com.example.orchestrator.artifacts.Artifacts.Criterion;
import com.example.orchestrator.artifacts.Artifacts.RequirementSource;
import com.example.orchestrator.artifacts.Artifacts.Spec;
import com.example.orchestrator.core.Json;
import com.example.orchestrator.core.OrchestratorException;
import com.example.orchestrator.model.Approval;
import com.example.orchestrator.model.Run;

/**
 * Claude-powered requirement analysis, used when a run is started with --llm.
 * On any failure (no credentials, refusal, invalid output, API error) the engine
 * retries and then falls back to the rule-based agent.
 */
public final class LlmRequirementsAgent implements Agent {

    private static final String MODEL = "claude-opus-5-5";

    private static final String SYSTEM_PROMPT = """
            You are the requirements analyst in an SDLC pipeline for a URL shortener service
            (Java 17, Spring Boot, Thymeleaf, MongoDB; a one-page UI whose form is CSRF-protected;
            public GET /{code} redirects).

            Turn the stakeholder's requirement into a normalized engineering spec:
            - acceptanceCriteria: concrete, testable statements, ids AC-1, AC-2, ... Set derived=true when you
              inferred a criterion rather than reading it from an explicit acceptance-criteria list.
            - ambiguities: only real gaps that would change what gets built. For each, give 2-4 concrete options.
              When an option matches one in the known-interpretations catalogue, reuse its id exactly.
              If the requirement already has explicit, measurable acceptance criteria, return no ambiguities.
            - keywords: 5-12 lowercase words a developer would grep for in the codebase.
            Do not invent requirements the stakeholder did not ask for.""";

    /** The shape Claude is asked to return (schema derived from these records). */
    public record LlmCriterion(String id, String text, boolean derived) {
    }

    public record LlmOption(String id, String description) {
    }

    public record LlmAmbiguity(String term, String question, List<LlmOption> options) {
    }

    public record LlmSpec(String title, String summary, List<LlmCriterion> acceptanceCriteria, List<LlmAmbiguity> ambiguities,
            List<String> keywords) {
    }

    @Override
    public String name() {
        return "requirements-llm";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String text = context.ctx().get("requirementSource", RequirementSource.class).text();
        Run.Note note = context.ctx().noteFor("requirements");
        String catalogue = Json.toTree(Interpretations.ALL.stream().map(k -> java.util.Map.of("term", k.term(), "options", k.options())).toList()).toString();
        String userContent = "<requirement>\n" + text + "\n</requirement>\n\n<known_interpretations>\n" + catalogue + "\n</known_interpretations>"
                + (note != null ? "\n\n<reviewer_note>\n" + note.note + "\n</reviewer_note>" : "");

        StructuredMessage<LlmSpec> message;
        try {
            AnthropicClient client = AnthropicOkHttpClient.fromEnv();
            StructuredMessageCreateParams<LlmSpec> params = MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(16000L)
                    .system(SYSTEM_PROMPT)
                    .outputConfig(LlmSpec.class)
                    .addUserMessage(userContent)
                    .build();
            context.log("asking " + MODEL + " to analyse the requirement");
            message = client.messages().create(params);
        } catch (UnauthorizedException | PermissionDeniedException e) {
            throw new OrchestratorException.NonRetryable("Claude API credentials rejected: " + e.getMessage());
        } catch (BadRequestException e) {
            throw new OrchestratorException.NonRetryable("Claude API rejected the request: " + e.getMessage());
        } catch (AnthropicServiceException e) {
            throw new OrchestratorException("Claude API error " + e.statusCode() + ": " + e.getMessage()); // retryable
        } catch (RuntimeException e) {
            throw new OrchestratorException.NonRetryable("Claude API unavailable: " + e.getMessage());
        }

        if (message.stopReason().map(StopReason.REFUSAL::equals).orElse(false)) {
            throw new OrchestratorException.NonRetryable("Model declined the request");
        }
        Optional<LlmSpec> parsed = message.content().stream().flatMap(block -> block.text().stream()).map(typed -> typed.text()).findFirst();
        if (parsed.isEmpty()) {
            throw new OrchestratorException("Model output did not match the spec schema");
        }

        Spec spec = toSpec(parsed.get(), note);
        return AgentResult.of("spec", spec)
                .approvalOptions(spec.ambiguities().isEmpty() ? null : spec.ambiguities().get(0).options())
                .decision("Claude normalized the requirement into " + spec.acceptanceCriteria().size() + " criteria and "
                        + spec.ambiguities().size() + " open question(s)", "Model " + MODEL)
                .summary(spec.acceptanceCriteria().size() + " criteria, " + spec.ambiguities().size() + " ambiguities (LLM)");
    }

    private static Spec toSpec(LlmSpec llm, Run.Note note) {
        List<Criterion> criteria = llm.acceptanceCriteria().stream().map(c -> new Criterion(c.id(), c.text(), c.derived(), false)).toList();
        List<Ambiguity> ambiguities = llm.ambiguities().stream()
                .map(a -> new Ambiguity(a.term(), a.question(), a.options().stream().map(o -> new Approval.Option(o.id(), o.description())).toList()))
                .toList();
        return new Spec(llm.title(), llm.summary(), criteria, ambiguities, llm.keywords(), MODEL, note == null ? null : note.note);
    }
}
