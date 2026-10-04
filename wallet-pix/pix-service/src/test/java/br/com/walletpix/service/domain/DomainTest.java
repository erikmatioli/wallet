package br.com.walletpix.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.walletpix.service.domain.PixPayment.PartyAccount;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DomainTest {

    private static final Instant NOW = Instant.parse("2026-10-04T09:05:00Z");
    private static final PartyAccount PARTY = new PartyAccount("X", "52998224725", "0001", "001000029");

    @Test
    void endToEndIdFollowsTheSpiFormat() {
        String e2e = SpiIds.newEndToEndId("12345678", NOW);

        assertThat(e2e).hasSize(32).startsWith("E12345678202610040905");
        assertThat(SpiIds.isEndToEndId(e2e)).isTrue();
        assertThat(SpiIds.isEndToEndId("E1234567820261004090")).isFalse();
        assertThat(SpiIds.newMessageId("12345678")).hasSize(32).startsWith("M12345678");
    }

    @Test
    void ispbMustHaveEightDigits() {
        assertThatThrownBy(() -> SpiIds.newEndToEndId("123", NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void amountsConvertExactlyAndRefuseFractionsOfACent() {
        assertThat(Amounts.toCents(new BigDecimal("10.5"))).isEqualTo(1050);
        assertThat(Amounts.toCents(new BigDecimal("10.500"))).isEqualTo(1050);
        assertThat(Amounts.toDecimal(1099)).isEqualByComparingTo("10.99");
        assertThatThrownBy(() -> Amounts.toCents(new BigDecimal("0.001"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Amounts.toCents(BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inboundPaymentCanOnlyBeCreditedOnceAndOnlyIfAccepted() {
        PixPayment accepted = PixPayment.acceptedIncoming("E1", "12345678", "99999999", "M1", 100, PARTY, PARTY,
                UUID.randomUUID(), null, NOW);
        PixPayment credited = accepted.credited(UUID.randomUUID(), NOW);

        assertThat(credited.status()).isEqualTo(PixPayment.Status.CREDITED);
        assertThatThrownBy(() -> credited.credited(UUID.randomUUID(), NOW))
                .isInstanceOf(PixPayment.InvalidTransitionException.class);
        PixPayment rejected = PixPayment.rejectedIncoming("E2", "12345678", "99999999", "M2", 100, PARTY, PARTY,
                RejectionReason.BE01, null, NOW);
        assertThatThrownBy(() -> rejected.credited(UUID.randomUUID(), NOW))
                .isInstanceOf(PixPayment.InvalidTransitionException.class);
    }

    @Test
    void outboundPaymentIsReturnedOnlyAfterCompletion() {
        PixPayment sent = PixPayment.sentOutgoing("E1", "12345678", "99999999", "M1", 100, PARTY, PARTY,
                UUID.randomUUID(), UUID.randomUUID(), "req", null, NOW);

        assertThatThrownBy(() -> sent.returned("MD06", UUID.randomUUID(), NOW))
                .isInstanceOf(PixPayment.InvalidTransitionException.class);
        assertThat(sent.completed(NOW).returned("MD06", UUID.randomUUID(), NOW).status())
                .isEqualTo(PixPayment.Status.RETURNED);
        assertThat(sent.refunded("AC03", UUID.randomUUID(), NOW).status()).isEqualTo(PixPayment.Status.REFUNDED);
    }

    @Test
    void holderCheckOutcomesMapToCatalogReasons() {
        assertThat(new HolderCheckResult(HolderCheckResult.Outcome.VALID, UUID.randomUUID()).rejectionReason()).isEmpty();
        assertThat(new HolderCheckResult(HolderCheckResult.Outcome.TAX_ID_MISMATCH, null).rejectionReason())
                .contains(RejectionReason.BE01);
    }
}
