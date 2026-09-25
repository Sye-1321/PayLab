package io.github.sye1321.paylab.run;

import io.github.sye1321.paylab.provider.IdempotencyKey;
import io.github.sye1321.paylab.provider.JdbcProviderPaymentStore;
import io.github.sye1321.paylab.provider.Payment;
import io.github.sye1321.paylab.provider.PaymentCreationResult;
import io.github.sye1321.paylab.provider.PaymentIntent;
import org.springframework.stereotype.Service;

@Service
public class ConcurrentDuplicateCreateScenarioExecutor {

    private final JdbcProviderPaymentStore payments;
    private final ConcurrentCreateRendezvous rendezvous;
    private final SuccessfulPaymentProgressor progressor;
    private final JdbcRunEventStore events;

    public ConcurrentDuplicateCreateScenarioExecutor(JdbcProviderPaymentStore payments,
            ConcurrentCreateRendezvous rendezvous, SuccessfulPaymentProgressor progressor,
            JdbcRunEventStore events) {
        this.payments = payments;
        this.rendezvous = rendezvous;
        this.progressor = progressor;
        this.events = events;
    }

    public Payment execute(TestRunId runId, IdempotencyKey key, PaymentIntent intent) {
        ConcurrentCreateRendezvous.Participation participation = null;
        if (!payments.existsByRunAndIdempotencyKey(runId, key)) {
            participation = rendezvous.awaitPeer(runId, key);
        }

        PaymentCreationResult creation;
        try {
            creation = payments.createOrResolve(runId, key, intent);
        } finally {
            if (participation != null) {
                rendezvous.complete(participation);
            }
        }
        events.appendPaymentRequestResolved(runId, key, intent.fingerprint(), creation.payment().id());
        Payment succeeded = progressor.progress(runId, creation.payment());
        if (creation.created()) {
            events.appendPaymentCommitted(runId, succeeded.id());
        }
        return succeeded;
    }
}
