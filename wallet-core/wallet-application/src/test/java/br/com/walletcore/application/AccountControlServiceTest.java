package br.com.walletcore.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.walletcore.application.port.in.AccountControlUseCase.BlockCommand;
import br.com.walletcore.application.port.in.AccountControlUseCase.CloseCommand;
import br.com.walletcore.application.port.in.AccountControlUseCase.ReleaseCommand;
import br.com.walletcore.application.port.in.AccountControlUseCase.TimelineKind;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.DepositCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.Destination;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.JudicialDestination;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.JudicialTransferCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.JudicialTransitOutCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.PixCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.ReversalCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.TransactionResult;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.TransferCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.WithdrawCommand;
import br.com.walletcore.application.port.in.QueryAccountUseCase.HolderCheck;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.account.AccountBlock;
import br.com.walletcore.domain.account.AccountStatusEvent;
import br.com.walletcore.domain.backing.BackingType;
import br.com.walletcore.domain.exception.DomainException;
import br.com.walletcore.domain.exception.ForbiddenException;
import br.com.walletcore.domain.ledger.TransactionType;
import br.com.walletcore.domain.pix.PixCounterparty;
import br.com.walletcore.domain.pix.PixDetail;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TransactionId;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** ADR-013: blocks that add up, the judicial transfer and its TED, the closing and the CCS feed. */
class AccountControlServiceTest {

    private static final String E2E_IN = "E99999999202610111200abcDEF12345";
    private static final String E2E_OUT = "E12345678202610111201abcDEF12345";

    private final InMemoryFixture f = new InMemoryFixture();
    private final PixCounterparty joao = PixCounterparty.of("Joao Souza", "529.982.247-25", "99999999", "0001",
            "7654321", "TRAN");
    private int keys;

    private Account funded(long cents) {
        Account a = f.openCustomerAccount();
        if (cents > 0) {
            deposit(a, cents);
        }
        return a;
    }

    private TransactionResult deposit(Account a, long cents) {
        return f.moveMoney.deposit(new DepositCommand(f.tenant, a.id(), Money.ofCents(cents), "seed", key()));
    }

    private TransactionResult withdraw(Account a, long cents) {
        return f.moveMoney.withdraw(new WithdrawCommand(f.tenant, a.id(), Money.ofCents(cents), "saque", key()));
    }

    private TransactionResult pix(Account a, TransactionType type, long cents, String e2e, String returnId,
                                  TransactionId related) {
        PixDetail detail = new PixDetail(type, e2e, returnId, related, joao, returnId == null ? null : "MD06", null);
        return f.moveMoney.postPix(new PixCommand(f.tenant, a.id(), Money.ofCents(cents), "pix", key(), detail));
    }

    private TransactionResult judicial(Account a, AccountBlock block, long cents, JudicialDestination to, String key) {
        return f.moveMoney.judicialTransfer(new JudicialTransferCommand(f.tenant, a.id(), block.id(),
                Money.ofCents(cents), to, "ofício 123", f.operator, key));
    }

    private TransactionResult ted(TransactionResult transfer, BackingType from, long cents) {
        return f.moveMoney.judicialTransitOut(new JudicialTransitOutCommand(f.tenant, transfer.id(), f.backing(from),
                Money.ofCents(cents), "TED 000987", null, f.operator, key()));
    }

    private AccountBlock release(Account a, AccountBlock b, Set<String> scopes) {
        return f.control.release(new ReleaseCommand(f.tenant, a.id(), b.id(), "análise concluída", f.operator, scopes));
    }

    private String key() {
        return "k-" + keys++;
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
    void aFraudBlockReceivesAndOnlyLetsTheMedOut() {
        Account a = funded(10_000);
        TransactionResult in = pix(a, TransactionType.PIX_IN, 5_000, E2E_IN, null, null);
        f.block(a, AccountBlock.Type.FRAUD);

        deposit(a, 1_000);
        pix(a, TransactionType.PIX_IN, 500, "E99999999202610111202abcDEF12345", null, null);
        assertThat(code(() -> withdraw(a, 100))).isEqualTo("ACCOUNT_BLOCKED");
        assertThat(code(() -> pix(a, TransactionType.PIX_OUT, 100, E2E_OUT, null, null))).isEqualTo("ACCOUNT_BLOCKED");
        // The MED: the return of the received Pix is the way out the fraud team keeps open.
        pix(a, TransactionType.PIX_RETURN_OUT, 5_000, E2E_IN, "D99999999202610111210abcDEF12345", in.id());

        // No sum of every balance here: the fixture has no rollback, the refused legs' other side stays applied.
        assertThat(f.balanceOf(a)).isEqualTo(10_000 + 1_000 + 500);
    }

    @Test
    void anAdministrativeBlockKeepsEverythingOutButRefunds() {
        Account a = funded(10_000);
        TransactionResult out = pix(a, TransactionType.PIX_OUT, 2_000, E2E_OUT, null, null);
        TransactionResult settled = pix(a, TransactionType.PIX_OUT, 3_000, "E12345678202610111203abcDEF12345", null,
                null);
        TransactionResult cash = withdraw(a, 1_000);
        TransactionResult in = pix(a, TransactionType.PIX_IN, 500, E2E_IN, null, null);
        f.block(a, AccountBlock.Type.ADMINISTRATIVE);

        assertThat(code(() -> deposit(a, 100))).isEqualTo("ACCOUNT_BLOCKED");
        assertThat(code(() -> withdraw(a, 100))).isEqualTo("ACCOUNT_BLOCKED");
        assertThat(code(() -> pix(a, TransactionType.PIX_RETURN_OUT, 500, E2E_IN, "D99999999202610111211abcDEF12345",
                in.id()))).isEqualTo("ACCOUNT_BLOCKED");
        // A Pix the SPI refused, one the payee returned and a withdrawal undone still come back, or the money
        // would be lost in the tenant.
        f.moveMoney.reverseWithdrawal(new ReversalCommand(f.tenant, out.id(), null, "AC06"));
        f.moveMoney.reverseWithdrawal(new ReversalCommand(f.tenant, cash.id(), null));
        pix(a, TransactionType.PIX_RETURN_IN, 1_000, "E12345678202610111203abcDEF12345",
                "D99999999202610111213abcDEF12345", settled.id());

        assertThat(f.balanceOf(a)).isEqualTo(10_000 + 500 - 3_000 + 1_000);
    }

    @Test
    void aJudicialBlockReceivesAndOnlyTheCourtOrderTakesMoneyOut() {
        Account a = funded(10_000);
        Account creditor = funded(0);
        AccountBlock order = f.block(a, AccountBlock.Type.JUDICIAL);

        deposit(a, 1_000);
        assertThat(code(() -> f.moveMoney.transfer(new TransferCommand(f.tenant, a.id(),
                new Destination.ById(creditor.id()), Money.ofCents(100), null, key())))).isEqualTo("ACCOUNT_BLOCKED");

        TransactionResult r = judicial(a, order, 4_000, new JudicialDestination.ToAccount(
                new Destination.ById(creditor.id())), "court-1");

        assertThat(r.type()).isEqualTo(TransactionType.JUDICIAL_TRANSFER);
        assertThat(r.description()).isEqualTo("Ordem judicial, processo 0001234-56.2026.8.26.0100");
        assertThat(f.balanceOf(a)).isEqualTo(7_000);
        assertThat(f.balanceOf(creditor)).isEqualTo(4_000);
        assertThat(f.judicialDetails.get(r.id()).blockId()).isEqualTo(order.id());
        assertThat(code(() -> judicial(a, order, 7_001, new JudicialDestination.ToTransit(), "court-2")))
                .isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void theCourtOrderPrevailsOverTheOtherBlocks() {
        Account a = funded(10_000);
        f.block(a, AccountBlock.Type.FRAUD);
        f.block(a, AccountBlock.Type.ADMINISTRATIVE);
        AccountBlock order = f.block(a, AccountBlock.Type.JUDICIAL);

        judicial(a, order, 10_000, new JudicialDestination.ToTransit(), "court-1");

        assertThat(f.balanceOf(a)).isZero();
        assertThat(f.accounts.get(f.judicialTransit()).balance().cents()).isEqualTo(10_000);
    }

    @Test
    void underFraudAndJudicialBlocksTheMedWaitsForTheCourt() {
        Account a = funded(0);
        TransactionResult in = pix(a, TransactionType.PIX_IN, 5_000, E2E_IN, null, null);
        f.block(a, AccountBlock.Type.FRAUD);
        AccountBlock order = f.block(a, AccountBlock.Type.JUDICIAL);

        assertThat(code(() -> pix(a, TransactionType.PIX_RETURN_OUT, 5_000, E2E_IN,
                "D99999999202610111212abcDEF12345", in.id()))).isEqualTo("ACCOUNT_BLOCKED");

        release(a, order, InMemoryFixture.ALL_SCOPES);
        pix(a, TransactionType.PIX_RETURN_OUT, 5_000, E2E_IN, "D99999999202610111212abcDEF12345", in.id());
        assertThat(f.balanceOf(a)).isZero();
    }

    @Test
    void eachBlockIsReleasedOnItsOwnAndOnlyWithItsScope() {
        Account a = funded(10_000);
        AccountBlock fraud = f.block(a, AccountBlock.Type.FRAUD);
        AccountBlock order = f.block(a, AccountBlock.Type.JUDICIAL);

        assertThatThrownBy(() -> release(a, order, Set.of("accounts:block"))).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> f.control.block(new BlockCommand(f.tenant, a.id(), AccountBlock.Type.JUDICIAL,
                AccountBlock.Reason.COURT_ORDER, null, new AccountBlock.CourtOrder("1", null), f.operator,
                Set.of("accounts:block")))).isInstanceOf(ForbiddenException.class);

        // The fraud team finishes its analysis: the court's block stays.
        release(a, fraud, Set.of("accounts:block"));
        assertThat(code(() -> withdraw(a, 100))).isEqualTo("ACCOUNT_BLOCKED");
        assertThat(code(() -> release(a, fraud, Set.of("accounts:block")))).isEqualTo("BLOCK_ALREADY_RELEASED");

        release(a, order, Set.of("accounts:judicial"));
        withdraw(a, 100);
        assertThat(f.accounts.get(a.id()).policy().canPay()).isTrue();
    }

    @Test
    void aBlockNeedsWhatItsTypeRequires() {
        Account a = funded(0);

        assertThat(code(() -> f.control.block(new BlockCommand(f.tenant, a.id(), AccountBlock.Type.JUDICIAL,
                AccountBlock.Reason.COURT_ORDER, null, null, f.operator, InMemoryFixture.ALL_SCOPES))))
                .isEqualTo("COURT_PROCESS_REQUIRED");
        assertThat(code(() -> f.control.block(new BlockCommand(f.tenant, a.id(), AccountBlock.Type.FRAUD,
                AccountBlock.Reason.OTHER, " ", null, f.operator, InMemoryFixture.ALL_SCOPES))))
                .isEqualTo("NOTE_REQUIRED");
        assertThat(code(() -> f.control.block(new BlockCommand(f.tenant, a.id(), AccountBlock.Type.FRAUD,
                AccountBlock.Reason.COURT_ORDER, null, null, f.operator, InMemoryFixture.ALL_SCOPES))))
                .isEqualTo("INVALID_BLOCK_REASON");
    }

    @Test
    void aReleasedOrderTransfersNothingButItsReplayIsStillAnswered() {
        Account a = funded(10_000);
        AccountBlock order = f.block(a, AccountBlock.Type.JUDICIAL);
        TransactionResult first = judicial(a, order, 1_000, new JudicialDestination.ToTransit(), "court-1");
        release(a, order, InMemoryFixture.ALL_SCOPES);

        assertThat(judicial(a, order, 1_000, new JudicialDestination.ToTransit(), "court-1").replayed()).isTrue();
        assertThat(code(() -> judicial(a, order, 1_000, new JudicialDestination.ToTransit(), "court-2")))
                .isEqualTo("JUDICIAL_BLOCK_NOT_ACTIVE");
        assertThat(first.replayed()).isFalse();
    }

    @Test
    void onlyAJudicialBlockOfTheSameAccountCarriesAnOrder() {
        Account a = funded(10_000);
        Account other = funded(10_000);
        AccountBlock fraud = f.block(a, AccountBlock.Type.FRAUD);
        AccountBlock otherOrder = f.block(other, AccountBlock.Type.JUDICIAL);

        assertThat(code(() -> judicial(a, fraud, 100, new JudicialDestination.ToTransit(), "c1")))
                .isEqualTo("JUDICIAL_BLOCK_NOT_FOUND");
        assertThat(code(() -> judicial(a, otherOrder, 100, new JudicialDestination.ToTransit(), "c2")))
                .isEqualTo("JUDICIAL_BLOCK_NOT_FOUND");
    }

    @Test
    void theTedsOfATransferLeaveTheTransitAndCannotExceedIt() {
        Account a = funded(10_000);
        AccountBlock order = f.block(a, AccountBlock.Type.JUDICIAL);
        TransactionResult transfer = judicial(a, order, 6_000, new JudicialDestination.ToTransit(), "court-1");
        assertThat(f.control.pendingJudicialTransit(f.tenant)).singleElement()
                .satisfies(p -> assertThat(p.remaining().cents()).isEqualTo(6_000));
        long piBefore = f.expectedOf(f.backing(BackingType.PIX_PI));

        TransactionResult ted = ted(transfer, BackingType.PIX_PI, 4_000);

        assertThat(ted.type()).isEqualTo(TransactionType.JUDICIAL_TRANSIT_OUT);
        assertThat(f.accounts.get(f.judicialTransit()).balance().cents()).isEqualTo(2_000);
        // The money left from where the TED was sent: its expected balance goes down.
        assertThat(f.expectedOf(f.backing(BackingType.PIX_PI))).isEqualTo(piBefore - 4_000);
        assertThat(code(() -> ted(transfer, BackingType.PIX_PI, 2_001))).isEqualTo("TRANSIT_OUT_EXCEEDS_TRANSFER");
        assertThat(code(() -> ted(transfer, BackingType.UNASSIGNED, 100))).isEqualTo("INVALID_TREASURY_SOURCE");

        ted(transfer, BackingType.CASH, 2_000);
        assertThat(f.control.pendingJudicialTransit(f.tenant)).isEmpty();
        assertThat(f.sumOfAllBalances()).isZero();
    }

    @Test
    void aTedNeedsAJudicialTransferToTheTransit() {
        Account a = funded(10_000);
        Account creditor = funded(0);
        AccountBlock order = f.block(a, AccountBlock.Type.JUDICIAL);
        TransactionResult toCustomer = judicial(a, order, 1_000,
                new JudicialDestination.ToAccount(new Destination.ById(creditor.id())), "court-1");

        assertThat(code(() -> ted(toCustomer, BackingType.PIX_PI, 1_000))).isEqualTo("JUDICIAL_TRANSFER_NOT_FOUND");
    }

    @Test
    void closingNeedsAnEmptyAccountWithoutACourtOrder() {
        Account a = funded(1_000);
        AccountBlock order = f.block(a, AccountBlock.Type.JUDICIAL);
        AccountBlock fraud = f.block(a, AccountBlock.Type.FRAUD);

        assertThat(code(() -> close(a))).isEqualTo("ACCOUNT_NOT_EMPTY");
        judicial(a, order, 1_000, new JudicialDestination.ToTransit(), "court-1");
        assertThat(code(() -> close(a))).isEqualTo("JUDICIAL_BLOCK_ACTIVE");
        release(a, order, InMemoryFixture.ALL_SCOPES);

        f.clock.now = f.clock.now.plus(Duration.ofMinutes(5));
        Account closed = close(a);

        assertThat(closed.status()).isEqualTo(Account.Status.CLOSED);
        assertThat(closed.closedAt()).isEqualTo(f.clock.now);
        assertThat(f.blocks.get(fraud.id()).release().note()).isEqualTo("Encerramento da conta");
        assertThat(code(() -> deposit(a, 100))).isEqualTo("ACCOUNT_NOT_ACTIVE");
        assertThat(code(() -> close(a))).isEqualTo("ACCOUNT_ALREADY_CLOSED");
        assertThat(code(() -> f.block(a, AccountBlock.Type.FRAUD))).isEqualTo("ACCOUNT_CLOSED");
        assertThat(f.statusEvents).last().satisfies(e -> {
            assertThat(e.from()).isEqualTo(Account.Status.ACTIVE);
            assertThat(e.to()).isEqualTo(Account.Status.CLOSED);
            assertThat(e.reason()).isEqualTo(AccountStatusEvent.CloseReason.CUSTOMER_REQUEST);
        });
    }

    @Test
    void theTimelineShowsBlocksReleasesAndTheClosingNewestFirst() {
        Account a = funded(0);
        AccountBlock fraud = f.block(a, AccountBlock.Type.FRAUD);
        f.clock.now = f.clock.now.plus(Duration.ofMinutes(1));
        release(a, fraud, InMemoryFixture.ALL_SCOPES);
        f.clock.now = f.clock.now.plus(Duration.ofMinutes(1));
        f.block(a, AccountBlock.Type.ADMINISTRATIVE);
        f.clock.now = f.clock.now.plus(Duration.ofMinutes(1));
        close(a);

        assertThat(f.control.timeline(f.tenant, a.id())).extracting(i -> i.kind()).containsExactly(
                TimelineKind.CLOSED, TimelineKind.RELEASED, TimelineKind.BLOCKED, TimelineKind.RELEASED,
                TimelineKind.BLOCKED);
    }

    @Test
    void theCcsFeedIsReadInOrderFromWhereTheLastPageStopped() {
        Account a = funded(0);
        Account b = funded(0);
        close(a);
        close(b);

        var first = f.control.ccsEvents(f.tenant, 0, 1);
        var second = f.control.ccsEvents(f.tenant, first.next(), 500);
        var empty = f.control.ccsEvents(f.tenant, second.next(), 500);

        assertThat(first.events()).singleElement().satisfies(e -> assertThat(e.accountId()).isEqualTo(a.id()));
        assertThat(second.events()).extracting(e -> e.accountId()).containsExactly(b.id());
        assertThat(empty.events()).isEmpty();
        assertThat(empty.next()).isEqualTo(second.next());
    }

    @Test
    void onlyAnAdministrativeBlockRefusesAPixAtTheHolderCheck() {
        Account fraud = f.openCustomerAccount("52998224725");
        Account court = f.openCustomerAccount("11144477735");
        f.block(fraud, AccountBlock.Type.FRAUD);
        f.block(court, AccountBlock.Type.JUDICIAL);

        assertThat(check(fraud, "52998224725").result()).isEqualTo(HolderCheck.Result.VALID);
        assertThat(check(court, "11144477735").result()).isEqualTo(HolderCheck.Result.VALID);
    }

    @Test
    void theAccountDetailCarriesItsActiveBlocks() {
        Account a = funded(0);
        AccountBlock fraud = f.block(a, AccountBlock.Type.FRAUD);
        AccountBlock order = f.block(a, AccountBlock.Type.JUDICIAL);
        release(a, fraud, InMemoryFixture.ALL_SCOPES);

        assertThat(f.query.getAccountDetail(f.tenant, a.id()).activeBlocks()).extracting(AccountBlock::id)
                .containsExactly(order.id());
    }

    private Account close(Account a) {
        return f.control.close(new CloseCommand(f.tenant, a.id(), AccountStatusEvent.CloseReason.CUSTOMER_REQUEST,
                null, f.operator));
    }

    private HolderCheck check(Account a, String taxId) {
        return f.query.checkHolder(f.tenant, a.number().branch(), a.number().number(), a.number().checkDigit(), taxId);
    }
}
