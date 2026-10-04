package br.com.walletpix.service.application;

import br.com.walletpix.messages.PixEvents.EventType;
import br.com.walletpix.messages.PixEvents.PixEvent;
import br.com.walletpix.service.domain.Amounts;
import br.com.walletpix.service.domain.PixPayment;

/** Events carry ids, amount and outcome only - never CPF/CNPJ or names (same rule as wallet-core's outbox). */
final class PixEventFactory {

    private PixEventFactory() {
    }

    static PixEvent of(EventType type, PixPayment p) {
        return new PixEvent(type, p.endToEndId(), p.requestId(), p.ispb(), p.walletAccountId(),
                Amounts.toDecimal(p.amountCents()), p.reasonCode(), p.walletTransactionId(), p.updatedAt());
    }
}
