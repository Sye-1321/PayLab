package io.github.sye1321.paylab.run;

import java.util.Objects;
import java.util.UUID;

public record TestRunId(UUID value) {

    public TestRunId {
        Objects.requireNonNull(value, "value");
    }
}
