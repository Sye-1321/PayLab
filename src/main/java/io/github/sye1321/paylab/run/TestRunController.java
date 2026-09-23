package io.github.sye1321.paylab.run;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

    public TestRunController(JdbcTestRunStore store, JdbcRunEventStore events) {
        this.store = store;
        this.events = events;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TestRunResponse create(@Valid @RequestBody CreateTestRunRequest request) {
        return TestRunResponse.from(store.create(request.scenario()));
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

    public record CreateTestRunRequest(@NotNull ScenarioId scenario) {
    }

    public record TestRunResponse(UUID runId, ScenarioId scenario, int scenarioVersion,
            Instant createdAt) {
        static TestRunResponse from(TestRun run) {
            return new TestRunResponse(run.runId().value(), run.scenario(), run.scenarioVersion(), run.createdAt());
        }
    }

    public record RunEventResponse(long eventId, UUID runId, RunEventType eventType, Instant occurredAt,
            String idempotencyKey, String requestFingerprint) {
        static RunEventResponse from(RunEvent event) {
            return new RunEventResponse(event.eventId(), event.runId().value(), event.eventType(),
                    event.occurredAt(), event.idempotencyKey(), event.requestFingerprint());
        }
    }
}
