package io.github.sye1321.paylab.run;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.sye1321.paylab.conformance.ConformanceEvaluation;
import io.github.sye1321.paylab.conformance.TimeoutAfterCommitConformanceEvaluator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test-runs")
public class TestRunController {

    private final JdbcTestRunStore store;
    private final JdbcRunEventStore events;
    private final WebhookUrlValidator webhookUrls;
    private final TimeoutAfterCommitConformanceEvaluator conformance;

    public TestRunController(JdbcTestRunStore store, JdbcRunEventStore events, WebhookUrlValidator webhookUrls,
            TimeoutAfterCommitConformanceEvaluator conformance) {
        this.store = store;
        this.events = events;
        this.webhookUrls = webhookUrls;
        this.conformance = conformance;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TestRunResponse create(@Valid @RequestBody CreateTestRunRequest request) {
        String webhookUrl;
        Integer responseDelayMillis;
        switch (request.scenario()) {
            case ASYNC_SUCCESS -> {
                if (request.webhookUrl() == null || request.webhookUrl().isBlank()
                        || request.responseDelayMillis() != null) {
                    throw new IllegalArgumentException("Invalid ASYNC_SUCCESS configuration");
                }
                webhookUrl = webhookUrls.validate(request.webhookUrl());
                responseDelayMillis = null;
            }
            case TIMEOUT_AFTER_COMMIT -> {
                if (request.webhookUrl() != null || request.responseDelayMillis() == null
                        || request.responseDelayMillis() <= 0 || request.responseDelayMillis() > 30_000) {
                    throw new IllegalArgumentException("Invalid TIMEOUT_AFTER_COMMIT configuration");
                }
                webhookUrl = null;
                responseDelayMillis = request.responseDelayMillis();
            }
            default -> throw new IllegalArgumentException("Unsupported scenario");
        }
        return TestRunResponse.from(store.create(request.scenario(), webhookUrl, responseDelayMillis));
    }

    @GetMapping("/{runId}")
    public TestRunResponse get(@PathVariable UUID runId) {
        return TestRunResponse.from(store.require(new TestRunId(runId)));
    }

    @GetMapping("/{runId}/events")
    public List<RunEventResponse> events(@PathVariable UUID runId) {
        TestRunId id = new TestRunId(runId);
        store.require(id);
        return events.findByRun(id).stream().map(RunEventResponse::from).toList();
    }

    @GetMapping("/{runId}/conformance")
    public ConformanceEvaluation conformance(@PathVariable UUID runId) {
        return conformance.evaluate(new TestRunId(runId));
    }

    public record CreateTestRunRequest(@NotNull ScenarioId scenario, String webhookUrl, Integer responseDelayMillis) {
    }

    public record TestRunResponse(UUID runId, ScenarioId scenario, int scenarioVersion,
            Instant createdAt, String webhookUrl, Integer responseDelayMillis) {
        static TestRunResponse from(TestRun run) {
            return new TestRunResponse(run.runId().value(), run.scenario(), run.scenarioVersion(), run.createdAt(),
                    run.webhookUrl(), run.responseDelayMillis());
        }
    }

    public record RunEventResponse(long eventId, UUID runId, RunEventType eventType, Instant occurredAt,
            String idempotencyKey, String requestFingerprint, UUID webhookEventId,
            Integer httpStatus, String outcome, String paymentId, Integer responseDelayMillis) {
        static RunEventResponse from(RunEvent event) {
            return new RunEventResponse(event.eventId(), event.runId().value(), event.eventType(),
                    event.occurredAt(), event.idempotencyKey(), event.requestFingerprint(),
                    event.webhookEventId(), event.httpStatus(), event.outcome(),
                    event.paymentId() == null ? null : event.paymentId().value(), event.responseDelayMillis());
        }
    }
}
