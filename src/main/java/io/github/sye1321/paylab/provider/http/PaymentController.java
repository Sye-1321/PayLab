package io.github.sye1321.paylab.provider.http;

import java.util.UUID;

import io.github.sye1321.paylab.provider.IdempotencyKey;
import io.github.sye1321.paylab.provider.MerchantReference;
import io.github.sye1321.paylab.provider.Money;
import io.github.sye1321.paylab.provider.PaymentId;
import io.github.sye1321.paylab.provider.PaymentIntent;
import io.github.sye1321.paylab.provider.PaymentNotFoundException;
import io.github.sye1321.paylab.run.PaymentRequestOrchestrator;
import io.github.sye1321.paylab.run.PaymentRequestResult;
import io.github.sye1321.paylab.run.ResponseDelayApplier;
import io.github.sye1321.paylab.run.TestRunId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentRequestOrchestrator requests;
    private final ResponseDelayApplier responseDelays;

    public PaymentController(PaymentRequestOrchestrator requests, ResponseDelayApplier responseDelays) {
        this.requests = requests;
        this.responseDelays = responseDelays;
    }

    @PostMapping
    public PaymentResponse create(@RequestHeader("PayLab-Run-Id") UUID runId,
            @RequestHeader("Idempotency-Key") @NotBlank String key,
            @Valid @RequestBody CreatePaymentRequest request) {
        PaymentIntent intent = new PaymentIntent(
                new Money(request.amountMinor(), request.currency()),
                new MerchantReference(request.merchantReference()));
        TestRunId testRunId = new TestRunId(runId);
        PaymentRequestResult result = requests.create(testRunId, new IdempotencyKey(key), intent);
        responseDelays.apply(testRunId, result.payment().id(), result.responseDelayMillis());
        return PaymentResponse.from(result.payment());
    }

    @GetMapping("/{paymentId}")
    public PaymentResponse get(@RequestHeader("PayLab-Run-Id") UUID runId, @PathVariable String paymentId) {
        PaymentId id = new PaymentId(paymentId);
        return PaymentResponse.from(requests.findById(new TestRunId(runId), id)
                .orElseThrow(() -> new PaymentNotFoundException(id)));
    }
}
