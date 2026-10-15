package br.com.walletcore.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.walletcore.application.port.in.AccountControlUseCase.BlockCommand;
import br.com.walletcore.application.port.in.AccountControlUseCase.CloseCommand;
import br.com.walletcore.application.port.in.AccountControlUseCase.ReleaseCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.DepositCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.FeeCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.FeeRefundCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.PixCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.ReversalCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.TransactionResult;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.WithdrawCommand;
import br.com.walletcore.application.port.in.QueryAccountUseCase.HolderCheck;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.account.AccountBlock;
import br.com.walletcore.domain.account.AccountPolicy;
import br.com.walletcore.domain.account.AccountStatusEvent;
import br.com.walletcore.domain.event.AccountBlockReleased;
import br.com.walletcore.domain.event.AccountBlocked;
import br.com.walletcore.domain.event.AccountClosed;
import br.com.walletcore.domain.event.DomainEvent;
import br.com.walletcore.domain.event.TransactionPosted;
import br.com.walletcore.domain.exception.DomainException;
import br.com.walletcore.domain.exception.ForbiddenException;
import br.com.walletcore.domain.ledger.TransactionType;
import br.com.walletcore.domain.pix.PixCounterparty;
import br.com.walletcore.domain.pix.PixDetail;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TransactionId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** ADR-014: fees into the tenant's revenue, their refund, the fee block and the facts the events carry. */
class FeeServiceTest {

    private final InMemoryFixture f = new InMemoryFixture();
    private int keys;

    private String key() {
        return "k-" + keys++;
    }

    private Account funded(long cents) {
        Account a = f.openCustomerAccount();
        if (cents > 0) {
            f.moveMoney.deposit(new DepositCommand(f.tenant, a.id(), Money.ofCents(cents), "seed", key()));
        }
        return a;
    }

    private TransactionResult fee(Account a, long cents, String operation, TransactionId source, String key) {
        return f.moveMoney.fee(new FeeCommand(f.tenant, a.id(), Money.ofCents(cents), operation, "Tarifa: " + operation,
                source, null, key));
    }

    private TransactionResult pixIn(Account a, long cents, String payerDocument, String e2e) {
        PixDetail detail = new PixDetail(TransactionType.PIX_IN, e2e, null, null,
                PixCounterparty.of("Pagador", payerDocument, "99999999", "0001", "123456", "TRAN"), null, null);
        return f.moveMoney.postPix(new PixCommand(f.tenant, a.id(), Money.ofCents(cents), "pix", key(), detail));
    }

    private AccountBlock feeBlock(Account a) {
        return f.control.block(new BlockCommand(f.tenant, a.id(), AccountBlock.Type.FEE_PENDING,
                AccountBlock.Reason.UNPAID_FEES, null, null, f.operator, Set.of("fees:write")));
    }

    private TransactionPosted posted(TransactionResult r) {
        return f.events.stream().filter(e -> e instanceof TransactionPosted p && p.transactionId().equals(r.id().value()))
                .map(TransactionPosted.class::cast).findFirst().orElseThrow();
    }

    private static String code(Executable action) {
        try {
            action.execute();
        } catch (DomainException e) {
            return e.code();
        } catch (Throwable e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("expected a refusal");
    }

    @Test
    void aFeeMovesMoneyFromTheCustomerToTheTenantsRevenue() {
        Account a = funded(10_000);
        TransactionResult pix = pixIn(a, 1_000, "52998224725", "E99999999202610151200abcDEF12345");

        TransactionResult r = fee(a, 150, "PIX_IN", pix.id(), "fee:" + pix.id().value() + ":PIX_IN");

        assertThat(r.type()).isEqualTo(TransactionType.FEE);
        assertThat(f.balanceOf(a)).isEqualTo(10_000 + 1_000 - 150);
        assertThat(f.accounts.get(f.tenantRevenue()).balance().cents()).isEqualTo(150);
        assertThat(f.feeDetails.get(r.id()).operation()).isEqualTo("PIX_IN");
        assertThat(f.feeDetails.get(r.id()).source()).isEqualTo(pix.id());
        assertThat(posted(r).relatedTransactionId()).isEqualTo(pix.id().value());
        assertThat(posted(r).reversal()).isFalse();
        // The same origin, asked again: the same fee, not a second one.
        assertThat(fee(a, 150, "PIX_IN", pix.id(), "fee:" + pix.id().value() + ":PIX_IN").replayed()).isTrue();
        assertThat(f.accounts.get(f.tenantRevenue()).balance().cents()).isEqualTo(150);
    }

    @Test
    void aFeeIsNeverChargedInPartNorPastABlock() {
        Account poor = funded(100);
        assertThat(code(() -> fee(poor, 150, "WITHDRAWAL", null, key()))).isEqualTo("INSUFFICIENT_FUNDS");

        Account blocked = funded(10_000);
        f.block(blocked, AccountBlock.Type.FRAUD);
        assertThat(code(() -> fee(blocked, 150, "WITHDRAWAL", null, key()))).isEqualTo("ACCOUNT_BLOCKED");
        assertThat(code(() -> f.moveMoney.fee(new FeeCommand(f.tenant, poor.id(), Money.ofCents(1), "pix out", "x", null,
                null, key())))).isEqualTo("INVALID_FEE_OPERATION");
    }

    @Test
    void theFeeBlockReceivesAndLetsOnlyTheFeeAndTheMedOut() {
        Account a = f.openCustomerAccount("52998224725");
        TransactionResult in = pixIn(a, 5_000, "11222333000181", "E99999999202610151201abcDEF12345");
        feeBlock(a);

        assertThat(f.accounts.get(a.id()).policy()).isEqualTo(new AccountPolicy(AccountPolicy.Credit.ALLOWED,
                AccountPolicy.Debit.FEE_ONLY));
        assertThat(code(() -> f.moveMoney.withdraw(new WithdrawCommand(f.tenant, a.id(), Money.ofCents(100), "", key()))))
                .isEqualTo("ACCOUNT_BLOCKED");
        f.moveMoney.deposit(new DepositCommand(f.tenant, a.id(), Money.ofCents(200), "crédito", key()));
        fee(a, 150, "MONTHLY", null, key());
        f.moveMoney.postPix(new PixCommand(f.tenant, a.id(), Money.ofCents(1_000), "med", key(),
                new PixDetail(TransactionType.PIX_RETURN_OUT, "E99999999202610151201abcDEF12345",
                        "D99999999202610151210abcDEF12345", in.id(),
                        PixCounterparty.of("Pagador", "11222333000181", "99999999", "0001", "123456", "TRAN"), "MD06",
                        null)));

        assertThat(f.balanceOf(a)).isEqualTo(5_000 + 200 - 150 - 1_000);
        assertThat(check(a).result()).isEqualTo(HolderCheck.Result.VALID); // it still receives Pix
    }

    @Test
    void underAJudicialBlockNotEvenTheFeeBlockLetsTheFeeOut() {
        Account a = funded(10_000);
        feeBlock(a);
        f.block(a, AccountBlock.Type.JUDICIAL);

        assertThat(code(() -> fee(a, 150, "MONTHLY", null, key()))).isEqualTo("ACCOUNT_BLOCKED");
    }

    @Test
    void onlyTheFeeBasketHandlesTheFeeBlock() {
        Account a = funded(0);
        assertThatThrownBy(() -> f.control.block(new BlockCommand(f.tenant, a.id(), AccountBlock.Type.FEE_PENDING,
                AccountBlock.Reason.UNPAID_FEES, null, null, f.operator, Set.of("accounts:block"))))
                .isInstanceOf(ForbiddenException.class);
        assertThat(code(() -> f.control.block(new BlockCommand(f.tenant, a.id(), AccountBlock.Type.FEE_PENDING,
                AccountBlock.Reason.OTHER, "x", null, f.operator, Set.of("fees:write"))))).isEqualTo("INVALID_BLOCK_REASON");
    }

    @Test
    void aFeeRefundGivesTheWholeFeeBackOnceEvenUnderABlock() {
        Account a = funded(10_000);
        TransactionResult charged = fee(a, 150, "TRANSFER_OUT", null, key());
        f.block(a, AccountBlock.Type.ADMINISTRATIVE);

        TransactionResult refund = f.moveMoney.feeRefund(new FeeRefundCommand(f.tenant, charged.id(), null));

        assertThat(refund.type()).isEqualTo(TransactionType.FEE_REFUND);
        assertThat(f.balanceOf(a)).isEqualTo(10_000);
        assertThat(f.accounts.get(f.tenantRevenue()).balance().cents()).isZero();
        assertThat(f.feeDetails.get(refund.id()).source()).isEqualTo(charged.id());
        assertThat(f.feeDetails.get(refund.id()).operation()).isEqualTo("TRANSFER_OUT");
        assertThat(posted(refund).reversal()).isTrue();
        assertThat(posted(refund).relatedTransactionId()).isEqualTo(charged.id().value());
        assertThat(f.moveMoney.feeRefund(new FeeRefundCommand(f.tenant, charged.id(), null)).replayed()).isTrue();
        assertThat(f.balanceOf(a)).isEqualTo(10_000);
    }

    @Test
    void onlyAFeeHasAFeeRefund() {
        Account a = funded(10_000);
        TransactionResult cash = f.moveMoney.withdraw(new WithdrawCommand(f.tenant, a.id(), Money.ofCents(100), "", key()));

        assertThat(code(() -> f.moveMoney.feeRefund(new FeeRefundCommand(f.tenant, cash.id(), null))))
                .isEqualTo("FEE_NOT_FOUND");
    }

    @Test
    void theTransactionEventSaysWhatItUndoesAndWhoPaidThePix() {
        Account a = funded(10_000);
        TransactionResult cash = f.moveMoney.withdraw(new WithdrawCommand(f.tenant, a.id(), Money.ofCents(100), "", key()));
        TransactionResult reversal = f.moveMoney.reverseWithdrawal(new ReversalCommand(f.tenant, cash.id(), null));
        TransactionResult fromPerson = pixIn(a, 100, "529.982.247-25", "E99999999202610151202abcDEF12345");
        TransactionResult fromCompany = pixIn(a, 100, "11.222.333/0001-81", "E99999999202610151203abcDEF12345");

        assertThat(posted(cash).reversal()).isFalse();
        assertThat(posted(cash).relatedTransactionId()).isNull();
        assertThat(posted(reversal).reversal()).isTrue();
        assertThat(posted(reversal).relatedTransactionId()).isEqualTo(cash.id().value());
        assertThat(posted(fromPerson).counterpartyDocumentType()).isEqualTo("CPF");
        assertThat(posted(fromCompany).counterpartyDocumentType()).isEqualTo("CNPJ");
        assertThat(f.pixDetails.get(fromCompany.id()).counterparty().taxIdMasked()).isEqualTo("***0181");
    }

    @Test
    void blocksReleasesAndClosingsArePublished() {
        Account a = funded(0);
        AccountBlock fraud = f.block(a, AccountBlock.Type.FRAUD);
        AccountBlock order = f.block(a, AccountBlock.Type.JUDICIAL);
        f.control.release(new ReleaseCommand(f.tenant, a.id(), fraud.id(), "ok", f.operator, InMemoryFixture.ALL_SCOPES));
        f.control.release(new ReleaseCommand(f.tenant, a.id(), order.id(), "ok", f.operator, InMemoryFixture.ALL_SCOPES));
        feeBlock(a);
        f.control.close(new CloseCommand(f.tenant, a.id(), AccountStatusEvent.CloseReason.CUSTOMER_REQUEST, null,
                f.operator));

        List<DomainEvent> accountEvents = f.events.stream().filter(e -> !(e instanceof TransactionPosted)).toList();
        assertThat(accountEvents).extracting(DomainEvent::eventType).containsExactly(
                "wallet.account.blocked.v1", "wallet.account.blocked.v1",
                "wallet.account.block-released.v1", "wallet.account.block-released.v1",
                "wallet.account.blocked.v1",
                "wallet.account.block-released.v1", "wallet.account.closed.v1");
        assertThat(((AccountBlocked) accountEvents.getFirst()).blockType()).isEqualTo("FRAUD");
        // The fraud block went, the judicial one stayed; then the last one went.
        assertThat(((AccountBlockReleased) accountEvents.get(2)).stillBlocked()).isTrue();
        assertThat(((AccountBlockReleased) accountEvents.get(3)).stillBlocked()).isFalse();
        // The closing releases the fee block on its way.
        assertThat(((AccountBlockReleased) accountEvents.get(5)).blockType()).isEqualTo("FEE_PENDING");
        assertThat(((AccountClosed) accountEvents.get(6)).reason()).isEqualTo("CUSTOMER_REQUEST");
    }

    private HolderCheck check(Account a) {
        return f.query.checkHolder(f.tenant, a.number().branch(), a.number().number(), a.number().checkDigit(),
                "52998224725");
    }
}
