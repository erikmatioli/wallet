package br.com.walletpix.service.application;

import br.com.walletpix.service.application.port.PixPorts.PaymentPolicy;
import br.com.walletpix.service.domain.Amounts;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * Largest single outgoing Pix. The first entry of the policy chain; time windows (e.g. the
 * night-time limit the BCB requires), per-customer daily caps and similar rules are further
 * {@link PaymentPolicy} implementations added to the same list.
 */
public final class MaxAmountPolicy implements PaymentPolicy {

    private final long maxCents;

    public MaxAmountPolicy(BigDecimal maxAmount) {
        this.maxCents = Amounts.toCents(maxAmount);
    }

    @Override
    public Optional<Refusal> evaluate(PaymentAttempt attempt) {
        if (attempt.amountCents() > maxCents) {
            return Optional.of(new Refusal("POLICY_MAX_AMOUNT",
                    "amount exceeds the per-transaction limit of " + Amounts.toDecimal(maxCents)));
        }
        return Optional.empty();
    }
}
