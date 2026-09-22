package io.github.sye1321.paylab.provider;

public final class IllegalPaymentTransitionException extends IllegalStateException {

    public IllegalPaymentTransitionException(PaymentStatus current, PaymentStatus requested) {
        super("Cannot transition payment from " + current + " to " + requested);
    }
}
