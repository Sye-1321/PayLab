package io.github.sye1321.paylab.conformance;

import java.util.List;

public record AssertionEvaluation(String assertionId, String invariantId, ConformanceVerdict verdict,
        String explanation, List<Long> evidenceEventIds) {
}
