package br.com.walletcore.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.walletcore.application.port.in.MoveMoneyUseCase.DepositCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.PixCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.ReversalCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.TransactionResult;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.WithdrawCommand;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.exception.BusinessRuleException;
import br.com.walletcore.domain.exception.ConflictException;
import br.com.walletcore.domain.exception.DomainException;
import br.com.walletcore.domain.exception.NotFoundException;
import br.com.walletcore.domain.exception.ValidationException;
import br.com.walletcore.domain.ledger.EntryDirection;
import br.com.walletcore.domain.ledger.LedgerEntry;
import br.com.walletcore.domain.ledger.TransactionType;
import br.com.walletcore.domain.pix.PixCounterparty;
import br.com.walletcore.domain.pix.PixDetail;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TransactionId;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** ADR-010: Pix types, their detail, and the rules that tie refunds and returns to the original. */
class PixTransactionServiceTest {

    private static final String E2E = "E12345678202610061200abcDEF12345";
    private static final String E2E_2 = "E12345678202610061201abcDEF12345";
    private static final String RTR_1 = "D99999999202610061210abcDEF12345";
    private static final String RTR_2 = "D99999999202610061211abcDEF12345";

    private final InMemoryFixture f = new InMemoryFixture();
    private final PixCounterparty maria = PixCounterparty.of("Maria Oliveira", "111.444.777-35", "99999999", "0001",
            "12345678", "TRAN");

    private Account fundedAccount(long cents) {
        Account a = f.openCustomerAccount();
        f.moveMoney.deposit(new DepositCommand(f.tenant, a.id(), Money.ofCents(cents), "seed", "seed-" + a.id()));
        return a;
    }

    private TransactionResult pix(Account a, TransactionType type, long cents, String e2e, String returnId,
                                  TransactionId related, String key) {
        PixDetail detail = new PixDetail(type, e2e, returnId, related, maria, returnId == null ? null : "MD06",
                "aluguel");
        return f.moveMoney.postPix(new PixCommand(f.tenant, a.id(), Money.ofCents(cents), "pix", key, detail));
    }

    private TransactionResult pixOut(Account a, long cents) {
        return pix(a, TransactionType.PIX_OUT, cents, E2E, null, null, "out-" + E2E);
    }

    private TransactionResult returnIn(Account a, TransactionResult original, long cents, String rtrId) {
        return pix(a, TransactionType.PIX_RETURN_IN, cents, E2E, rtrId, original.id(), "ret-" + rtrId);
    }

    private static String code(Throwable e) {
        return ((DomainException) e).code();
    }

    @Test
    void pixInCreditsTheCustomerAndKeepsTheDetail() {
        Account a = f.openCustomerAccount();
        TransactionResult r = pix(a, TransactionType.PIX_IN, 5_000, E2E, null, null, "k1");

        assertThat(r.type()).isEqualTo(TransactionType.PIX_IN);
        assertThat(f.balanceOf(a)).isEqualTo(5_000);
        assertThat(f.sumOfAllBalances()).isZero();
        PixDetail stored = f.pixDetails.get(r.id());
        assertThat(stored.counterparty().taxIdMasked()).isEqualTo("***7735"); // never the full CPF
        assertThat(stored.counterparty().name()).isEqualTo("Maria Oliveira");
    }

    @Test
    void pixOutDebitsAndCannotOverdraw() {
        Account a = fundedAccount(10_000);
        pixOut(a, 4_000);
        assertThat(f.balanceOf(a)).isEqualTo(6_000);

        assertThatThrownBy(() -> pix(a, TransactionType.PIX_OUT, 6_001, E2E_2, null, null, "k2"))
                .extracting(PixTransactionServiceTest::code).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(f.pixDetails).hasSize(1); // the refused Pix left no detail behind
    }

    @Test
    void sameKeyReplaysAndAnotherKeyForTheSamePixIsAConflict() {
        Account a = f.openCustomerAccount();
        TransactionResult first = pix(a, TransactionType.PIX_IN, 1_000, E2E, null, null, "k1");
        TransactionResult replay = pix(a, TransactionType.PIX_IN, 1_000, E2E, null, null, "k1");

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.id()).isEqualTo(first.id());
        assertThatThrownBy(() -> pix(a, TransactionType.PIX_IN, 1_000, E2E, null, null, "k2"))
                .isInstanceOf(ConflictException.class)
                .extracting(PixTransactionServiceTest::code).isEqualTo("PIX_ALREADY_POSTED");
        assertThat(f.balanceOf(a)).isEqualTo(1_000);
    }

    @Test
    void aPixOutRetriedWithANewEndToEndIdReplaysAndReturnsTheFirstOne() {
        Account a = fundedAccount(10_000);
        TransactionResult first = pix(a, TransactionType.PIX_OUT, 1_000, E2E, null, null, "pix-debit-req-1");
        // the Pix service mints a new EndToEndId on every attempt of the same request
        TransactionResult retry = pix(a, TransactionType.PIX_OUT, 1_000, E2E_2, null, null, "pix-debit-req-1");

        assertThat(first.endToEndId()).isEqualTo(E2E);
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(retry.endToEndId()).isEqualTo(E2E); // the caller must use the stored one
        assertThat(f.balanceOf(a)).isEqualTo(9_000);
        // but the same key for another payee is another request
        PixDetail otherPayee = new PixDetail(TransactionType.PIX_OUT, E2E_2, null, null,
                PixCounterparty.of("Joao", "52998224725", "99999999", "0001", "777", null), null, null);
        assertThatThrownBy(() -> f.moveMoney.postPix(new PixCommand(f.tenant, a.id(), Money.ofCents(1_000), "pix",
                "pix-debit-req-1", otherPayee))).extracting(PixTransactionServiceTest::code)
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void aPixBetweenTwoAccountsOfTheSameTenantIsOneOutAndOneInWithTheSameEndToEndId() {
        Account payer = fundedAccount(1_000);
        Account payee = f.openCustomerAccount();
        pix(payer, TransactionType.PIX_OUT, 300, E2E, null, null, "out");
        pix(payee, TransactionType.PIX_IN, 300, E2E, null, null, "in");

        assertThat(f.balanceOf(payer)).isEqualTo(700);
        assertThat(f.balanceOf(payee)).isEqualTo(300);
    }

    @Test
    void refundOfAPixOutCreditsBackOnceAndKeepsThePixDetail() {
        Account a = fundedAccount(10_000);
        TransactionResult out = pixOut(a, 4_000);

        TransactionResult refund = f.moveMoney.reverseWithdrawal(new ReversalCommand(f.tenant, out.id(), null, "AC03"));
        TransactionResult again = f.moveMoney.reverseWithdrawal(new ReversalCommand(f.tenant, out.id(), null, "AC03"));

        assertThat(refund.type()).isEqualTo(TransactionType.PIX_REFUND);
        assertThat(again.replayed()).isTrue();
        assertThat(f.balanceOf(a)).isEqualTo(10_000);
        PixDetail detail = f.pixDetails.get(refund.id());
        assertThat(detail.endToEndId()).isEqualTo(E2E);
        assertThat(detail.relatedTransactionId()).isEqualTo(out.id());
        assertThat(detail.reasonCode()).isEqualTo("AC03");
        assertThat(detail.counterparty()).isEqualTo(maria);
    }

    @Test
    void reversingAWithdrawalIsStillADeposit() {
        Account a = fundedAccount(1_000);
        TransactionResult w = f.moveMoney.withdraw(new WithdrawCommand(f.tenant, a.id(), Money.ofCents(500), "", "w"));
        assertThat(f.moveMoney.reverseWithdrawal(new ReversalCommand(f.tenant, w.id(), null)).type())
                .isEqualTo(TransactionType.DEPOSIT);
    }

    @Test
    void partialReturnsAreAcceptedUpToTheOriginalAmount() {
        Account a = fundedAccount(10_000);
        TransactionResult out = pixOut(a, 4_000);

        returnIn(a, out, 1_500, RTR_1);
        returnIn(a, out, 2_500, RTR_2);
        assertThat(f.balanceOf(a)).isEqualTo(10_000);

        assertThatThrownBy(() -> returnIn(a, out, 1, "D99999999202610061212abcDEF12345"))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(PixTransactionServiceTest::code).isEqualTo("PIX_RETURN_EXCEEDS_ORIGINAL");
    }

    @Test
    void aFullReturnReplayedIsAnsweredNotRefused() {
        Account a = fundedAccount(10_000);
        TransactionResult out = pixOut(a, 4_000);
        TransactionResult first = returnIn(a, out, 4_000, RTR_1);

        TransactionResult replay = returnIn(a, out, 4_000, RTR_1);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.id()).isEqualTo(first.id());
    }

    @Test
    void aRefundedPixCannotBeReturnedAndAReturnedPixCannotBeRefunded() {
        Account a = fundedAccount(10_000);
        TransactionResult refunded = pixOut(a, 1_000);
        f.moveMoney.reverseWithdrawal(new ReversalCommand(f.tenant, refunded.id(), null, "AC03"));
        assertThatThrownBy(() -> returnIn(a, refunded, 1_000, RTR_1))
                .extracting(PixTransactionServiceTest::code).isEqualTo("PIX_ALREADY_REFUNDED");

        TransactionResult returned = pix(a, TransactionType.PIX_OUT, 1_000, E2E_2, null, null, "out-2");
        pix(a, TransactionType.PIX_RETURN_IN, 100, E2E_2, RTR_2, returned.id(), "ret-2");
        assertThatThrownBy(() -> f.moveMoney.reverseWithdrawal(new ReversalCommand(f.tenant, returned.id(), null)))
                .extracting(PixTransactionServiceTest::code).isEqualTo("PIX_ALREADY_RETURNED");
    }

    @Test
    void aReturnMustReferToAPixOfTheRightTypeOnTheSameAccount() {
        Account a = fundedAccount(10_000);
        Account other = fundedAccount(10_000);
        TransactionResult out = pixOut(a, 1_000);
        TransactionResult in = pix(a, TransactionType.PIX_IN, 1_000, E2E_2, null, null, "in");

        // the PIX_OUT belongs to another account
        assertThatThrownBy(() -> returnIn(other, out, 100, RTR_1)).isInstanceOf(NotFoundException.class);
        // a received return must point at a PIX_OUT, not a PIX_IN
        assertThatThrownBy(() -> pix(a, TransactionType.PIX_RETURN_IN, 100, E2E_2, RTR_1, in.id(), "r"))
                .extracting(PixTransactionServiceTest::code).isEqualTo("PIX_ORIGINAL_NOT_FOUND");
    }

    @Test
    void aReturnSentDebitsUpToThePixReceived() {
        Account a = f.openCustomerAccount();
        TransactionResult in = pix(a, TransactionType.PIX_IN, 1_000, E2E, null, null, "in");

        pix(a, TransactionType.PIX_RETURN_OUT, 600, E2E, RTR_1, in.id(), "r1");
        assertThat(f.balanceOf(a)).isEqualTo(400);
        assertThatThrownBy(() -> pix(a, TransactionType.PIX_RETURN_OUT, 401, E2E, RTR_2, in.id(), "r2"))
                .extracting(PixTransactionServiceTest::code).isEqualTo("PIX_RETURN_EXCEEDS_ORIGINAL");
    }

    @Test
    void aRefundCannotBePostedDirectly() {
        Account a = fundedAccount(1_000);
        TransactionResult out = pixOut(a, 100);
        assertThatThrownBy(() -> pix(a, TransactionType.PIX_REFUND, 100, E2E, null, out.id(), "refund"))
                .isInstanceOf(ValidationException.class)
                .extracting(PixTransactionServiceTest::code).isEqualTo("INVALID_PIX_TYPE");
    }

    @Test
    void statementCanBeFilteredToPixAndCarriesTheDetail() {
        Account a = fundedAccount(10_000);
        TransactionResult out = pixOut(a, 1_000);
        f.moveMoney.reverseWithdrawal(new ReversalCommand(f.tenant, out.id(), null, "AC03"));

        var all = f.query.getStatement(f.tenant, a.id(), null, 10);
        var onlyPix = f.query.getStatement(f.tenant, a.id(), null, 10,
                Set.of(TransactionType.PIX_OUT, TransactionType.PIX_REFUND));

        assertThat(all.entries()).hasSize(3);
        assertThat(onlyPix.entries()).extracting(se -> se.entry().type())
                .containsExactly(TransactionType.PIX_REFUND, TransactionType.PIX_OUT);
        assertThat(onlyPix.entries()).allSatisfy(se -> {
            assertThat(se.pix()).isNotNull();
            assertThat(se.pix().counterparty().name()).isEqualTo("Maria Oliveira");
        });
        LedgerEntry refundEntry = onlyPix.entries().get(0).entry();
        assertThat(refundEntry.direction()).isEqualTo(EntryDirection.CREDIT);
        assertThat(all.entries().stream().filter(se -> se.entry().type() == TransactionType.DEPOSIT))
                .allSatisfy(se -> assertThat(se.pix()).isNull());
    }
}
