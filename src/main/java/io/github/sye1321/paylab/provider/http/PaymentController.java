package io.github.sye1321.paylab.provider.http;

import io.github.sye1321.paylab.provider.IdempotencyKey;
import io.github.sye1321.paylab.provider.JdbcProviderPaymentStore;
import io.github.sye1321.paylab.provider.MerchantReference;
import io.github.sye1321.paylab.provider.Money;
import io.github.sye1321.paylab.provider.PaymentId;
import io.github.sye1321.paylab.provider.PaymentIntent;
import io.github.sye1321.paylab.provider.PaymentNotFoundException;
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

    private final JdbcProviderPaymentStore store;

    public PaymentController(JdbcProviderPaymentStore store) {
        this.store = store;
    }

    @PostMapping
    public PaymentResponse create(@RequestHeader("Idempotency-Key") @NotBlank String key,
            @Valid @RequestBody CreatePaymentRequest request) {
        PaymentIntent intent = new PaymentIntent(
                new Money(request.amountMinor(), request.currency()),
                new MerchantReference(request.merchantReference()));
        return PaymentResponse.from(store.createOrResolve(new IdempotencyKey(key), intent));
    }

    @GetMapping("/{paymentId}")
    public PaymentResponse get(@PathVariable String paymentId) {
        PaymentId id = new PaymentId(paymentId);
        return PaymentResponse.from(store.findById(id).orElseThrow(() -> new PaymentNotFoundException(id)));
    }
}
