using Fees.Application;
using Fees.Domain;

namespace Fees.Tests;

// From a transaction to a fee, and from a fee to the core (ADR-001, decisions 4 and 5), with the ports in memory.
public class ChargeTests
{
    private readonly Fixture f = new();
    private readonly Guid pi = Guid.CreateVersion7(); // an internal account of the core

    private async Task<Guid> OpenAsync(string documentType = "CPF", long balanceCents = 0)
    {
        var account = Guid.CreateVersion7();
        await f.AccountService.OnOpenedAsync(Guid.CreateVersion7(), f.Tenant, account, documentType, f.Clock.Now);
        f.Core.Balances[account] = balanceCents;
        return account;
    }

    private PostedTransaction Tx(string type, Guid debit, Guid credit, long cents = 10_000,
        string? counterparty = null, bool reversal = false, Guid? related = null) =>
        new(Guid.CreateVersion7(), f.Tenant, Guid.CreateVersion7(), type,
            [new PostedEntry(debit, "DEBIT", cents), new PostedEntry(credit, "CREDIT", cents)], f.Clock.Now, related,
            reversal, counterparty);

    // The reversal of a transaction, as the core posts it: the money back to the account, pointing at it.
    private PostedTransaction Reversal(PostedTransaction of, Guid account) =>
        Tx(of.Type == "PIX_OUT" ? "PIX_REFUND" : "DEPOSIT", pi, account, reversal: true, related: of.TransactionId);

    private Task PostAsync(PostedTransaction t) => f.ChargeService.OnTransactionPostedAsync(t);

    private IEnumerable<FeeCharge> ChargesOf(Guid account) => f.Charges.Where(c => c.AccountId == account);

    [Fact]
    public async Task TheFreeTransfersAreFreeAndTheNextOneIsCharged()
    {
        var alice = await OpenAsync(balanceCents: 1_000);
        var bob = await OpenAsync();

        for (var i = 0; i < 3; i++)
        {
            await PostAsync(Tx("TRANSFER", alice, bob));
        }

        var charge = Assert.Single(ChargesOf(alice));
        Assert.Equal(ChargeStatus.CHARGED, charge.Status);
        Assert.Equal(100, charge.AmountCents);
        Assert.Equal("Tarifa: transferência", charge.Description);
        Assert.Equal(900, f.Core.Balances[alice]);
        Assert.Empty(ChargesOf(bob)); // receiving a transfer is not priced
    }

    [Fact]
    public async Task APixReceivedIsPricedByWhoPaidIt()
    {
        var shop = await OpenAsync("CNPJ", 1_000);
        await f.PackageService.AddScheduleAsync(f.Tenant, f.PackageOf("PJ_BASIC").Id, new ScheduleCommand(
            f.Today.AddDays(1), 0, [new OperationPrice(Operation.PIX_IN, 0, 20, 50)], "maria"));
        f.Clock.Now = f.Clock.Now.AddDays(1);

        await PostAsync(Tx("PIX_IN", pi, shop, counterparty: "CPF"));
        await PostAsync(Tx("PIX_IN", pi, shop, counterparty: "CNPJ"));
        await PostAsync(Tx("PIX_IN", pi, shop)); // posted before the core kept the type: as from a person

        Assert.Equal([20, 50, 20], ChargesOf(shop).Select(c => c.AmountCents).ToArray());
        Assert.All(ChargesOf(shop), c => Assert.Equal(ChargeStatus.CHARGED, c.Status));
    }

    [Fact]
    public async Task WithoutBalanceTheFeeWaitsAndTheAccountGetsOneFeeBlock()
    {
        var alice = await OpenAsync(balanceCents: 200);
        var bob = await OpenAsync();

        await PostAsync(Tx("PIX_OUT", alice, pi)); // 1.50, charged
        await PostAsync(Tx("PIX_OUT", alice, pi)); // 1.50 does not fit in 0.50
        await PostAsync(Tx("WITHDRAWAL", alice, pi)); // 1.00, behind the other: oldest first

        Assert.Equal([ChargeStatus.CHARGED, ChargeStatus.PENDING, ChargeStatus.PENDING],
            ChargesOf(alice).Select(c => c.Status).ToArray());
        Assert.Equal(50, f.Core.Balances[alice]); // never in part
        Assert.Contains(alice, f.Core.FeeBlocks);
        Assert.Equal(1, f.Core.BlocksPut);
        Assert.Equal("INSUFFICIENT_FUNDS", ChargesOf(alice).ElementAt(1).LastError);
        Assert.Empty(ChargesOf(bob));
    }

    [Fact]
    public async Task ACreditChargesThePendingFeesAndReleasesTheFeeBlock()
    {
        var alice = await OpenAsync();
        var bob = await OpenAsync(balanceCents: 10_000);
        await PostAsync(Tx("PIX_OUT", alice, pi));
        await PostAsync(Tx("WITHDRAWAL", alice, pi));
        Assert.Contains(alice, f.Core.FeeBlocks);

        f.Core.Balances[alice] = 1_000; // the credit the core posted
        await PostAsync(Tx("TRANSFER", bob, alice));

        Assert.All(ChargesOf(alice), c => Assert.Equal(ChargeStatus.CHARGED, c.Status));
        Assert.Equal(750, f.Core.Balances[alice]);
        Assert.DoesNotContain(alice, f.Core.FeeBlocks);
    }

    [Fact]
    public async Task ACreditThatCoversOnlyTheOldestKeepsTheBlock()
    {
        var alice = await OpenAsync();
        await PostAsync(Tx("PIX_OUT", alice, pi));
        await PostAsync(Tx("PIX_OUT", alice, pi));

        f.Core.Balances[alice] = 200;
        await PostAsync(Tx("DEPOSIT", pi, alice));

        Assert.Equal([ChargeStatus.CHARGED, ChargeStatus.PENDING], ChargesOf(alice).Select(c => c.Status).ToArray());
        Assert.Contains(alice, f.Core.FeeBlocks);
    }

    [Fact]
    public async Task ABlockedAccountWaitsWithoutAFeeBlockUntilTheRelease()
    {
        var alice = await OpenAsync(balanceCents: 1_000);
        f.Core.OtherBlocks.Add(alice);

        await PostAsync(Tx("PIX_OUT", alice, pi));

        Assert.Equal(ChargeStatus.PENDING, ChargesOf(alice).Single().Status);
        Assert.DoesNotContain(alice, f.Core.FeeBlocks);

        f.Core.OtherBlocks.Remove(alice);
        await f.ChargeService.OnBlockReleasedAsync(f.Tenant, alice, "FRAUD");

        Assert.Equal(ChargeStatus.CHARGED, ChargesOf(alice).Single().Status);
    }

    [Fact]
    public async Task TheReleaseOfTheFeeBlockItselfChargesNothing()
    {
        var alice = await OpenAsync();
        await PostAsync(Tx("PIX_OUT", alice, pi));
        f.Core.Balances[alice] = 1_000; // a balance whose credit event was not read

        await f.ChargeService.OnBlockReleasedAsync(f.Tenant, alice, "FEE_PENDING");

        Assert.Equal(ChargeStatus.PENDING, ChargesOf(alice).Single().Status);
    }

    [Fact]
    public async Task AClosedAccountHasItsFeesCancelled()
    {
        var alice = await OpenAsync();
        await PostAsync(Tx("PIX_OUT", alice, pi));
        await PostAsync(Tx("PIX_OUT", alice, pi));

        await f.ChargeService.CancelPendingAsync(f.Tenant, alice, "ACCOUNT_CLOSED");

        Assert.All(ChargesOf(alice), c => Assert.Equal(ChargeStatus.CANCELLED, c.Status));
    }

    [Fact]
    public async Task TheCoreRefusingAClosedAccountCancelsTheFee()
    {
        var alice = await OpenAsync(balanceCents: 1_000);
        f.Core.Closed.Add(alice);

        await PostAsync(Tx("PIX_OUT", alice, pi));

        Assert.Equal(ChargeStatus.CANCELLED, ChargesOf(alice).Single().Status);
        Assert.DoesNotContain(alice, f.Core.FeeBlocks);
    }

    [Fact]
    public async Task TheSameEventTwiceIsCountedAndChargedOnce()
    {
        var alice = await OpenAsync(balanceCents: 1_000);
        var t = Tx("PIX_OUT", alice, pi);

        await PostAsync(t);
        await PostAsync(t);
        await PostAsync(t with { EventId = Guid.CreateVersion7() }); // the same transaction, another event

        Assert.Single(ChargesOf(alice));
        Assert.Single(f.Usage);
        Assert.Equal(850, f.Core.Balances[alice]);
    }

    [Fact]
    public async Task AFeeLeftPendingByTheCoreBeingDownIsChargedByTheSweep()
    {
        var alice = await OpenAsync(balanceCents: 1_000);
        f.Core.Down = true;
        await PostAsync(Tx("PIX_OUT", alice, pi));
        Assert.Equal("UNAVAILABLE", ChargesOf(alice).Single().LastError);
        Assert.DoesNotContain(alice, f.Core.FeeBlocks); // no balance was not the reason

        f.Core.Down = false;
        foreach (var (tenant, account) in await f.ChargeService.AccountsWithPendingAsync())
        {
            await f.ChargeService.ChargePendingAsync(tenant, account);
        }

        Assert.Equal(ChargeStatus.CHARGED, ChargesOf(alice).Single().Status);
    }

    [Fact]
    public async Task AnAccountNotSeenYetIsAskedToTheCoreAndJoinsItsBasicPackage()
    {
        var shop = Guid.CreateVersion7();
        f.Core.Accounts[shop] = new CoreAccount(shop, "CNPJ", "ACTIVE");
        f.Core.Balances[shop] = 1_000;
        await f.PackageService.EnsureDefaultsAsync(f.Tenant);

        await PostAsync(Tx("PIX_OUT", shop, pi));

        Assert.Equal(f.PackageOf("PJ_BASIC").Id, f.Accounts[shop].PackageId);
        Assert.Equal(ChargeStatus.CHARGED, ChargesOf(shop).Single().Status);
        Assert.False(f.Accounts.ContainsKey(pi)); // the core does not know it as a customer's
    }

    [Fact]
    public async Task NeitherAReversalNorADepositNorAFeeIsPriced()
    {
        var alice = await OpenAsync(balanceCents: 1_000);

        await PostAsync(Tx("WITHDRAWAL", pi, alice, reversal: true));
        await PostAsync(Tx("DEPOSIT", pi, alice));
        await PostAsync(Tx("PIX_RETURN_OUT", alice, pi));
        await PostAsync(Tx("FEE", alice, pi));

        Assert.Empty(ChargesOf(alice));
        Assert.Empty(f.Usage);
    }

    [Fact]
    public async Task TheAccountViewShowsTheQuotaAndWhatIsPending()
    {
        var alice = await OpenAsync(balanceCents: 0);
        var bob = await OpenAsync();
        await PostAsync(Tx("TRANSFER", alice, bob));
        await PostAsync(Tx("PIX_OUT", alice, pi));

        var view = await f.ChargeService.ViewAsync(f.Tenant, alice);

        Assert.Equal("2026-10", view.Month);
        var transfers = view.Usage.Single(u => u.Operation == Operation.TRANSFER_OUT);
        Assert.Equal((1, 2), (transfers.Used, transfers.Free));
        Assert.Equal(150, view.PendingCents);
        Assert.Single(view.Charges);
    }

    [Fact]
    public async Task AReversalGivesTheChargedFeeBackAndItsPlaceInTheQuota()
    {
        var alice = await OpenAsync(balanceCents: 1_000);
        var bob = await OpenAsync();
        var withdrawal = Tx("WITHDRAWAL", alice, pi);
        await PostAsync(withdrawal);
        Assert.Equal(900, f.Core.Balances[alice]);

        await PostAsync(Reversal(withdrawal, alice));
        await PostAsync(Reversal(withdrawal, alice) with { EventId = Guid.CreateVersion7() }); // read twice

        var fee = ChargesOf(alice).Single();
        Assert.Equal(ChargeStatus.REFUNDED, fee.Status);
        Assert.Equal(f.Core.Refunds[fee.CoreTransactionId!.Value], fee.RefundTransactionId);
        Assert.Equal(1_000, f.Core.Balances[alice]); // given back once
        Assert.Equal("Estorno de tarifa: saque", fee.RefundDescription);

        // Two free transfers, one reversed: its place returns, so the next one is still free.
        var first = Tx("TRANSFER", alice, bob);
        await PostAsync(first);
        await PostAsync(Tx("TRANSFER", alice, bob));
        await PostAsync(Reversal(first, alice));
        await PostAsync(Tx("TRANSFER", alice, bob));
        Assert.DoesNotContain(ChargesOf(alice), c => c.Operation == "TRANSFER_OUT");
        await PostAsync(Tx("TRANSFER", alice, bob));
        Assert.Single(ChargesOf(alice), c => c.Operation == "TRANSFER_OUT");
    }

    [Fact]
    public async Task AReversalCancelsAPendingFeeAndReleasesTheFeeBlockWhenNothingIsLeft()
    {
        var alice = await OpenAsync();
        var pix = Tx("PIX_OUT", alice, pi);
        await PostAsync(pix);
        Assert.Contains(alice, f.Core.FeeBlocks);

        await PostAsync(Reversal(pix, alice));

        Assert.Equal(ChargeStatus.CANCELLED, ChargesOf(alice).Single().Status);
        Assert.Equal("REVERSED", ChargesOf(alice).Single().LastError);
        Assert.DoesNotContain(alice, f.Core.FeeBlocks);
    }

    [Fact]
    public async Task AReversalReadBeforeItsTransactionLeavesItWithoutFee()
    {
        var alice = await OpenAsync(balanceCents: 1_000);
        var pix = Tx("PIX_OUT", alice, pi);

        await PostAsync(Reversal(pix, alice));
        await PostAsync(pix);

        Assert.Empty(ChargesOf(alice));
        Assert.Equal(1_000, f.Core.Balances[alice]);
    }

    [Fact]
    public async Task APixReturnDoesNotTouchTheFee()
    {
        var alice = await OpenAsync(balanceCents: 1_000);
        var pix = Tx("PIX_OUT", alice, pi);
        await PostAsync(pix);

        await PostAsync(Tx("PIX_RETURN_IN", pi, alice, related: pix.TransactionId));

        Assert.Equal(ChargeStatus.CHARGED, ChargesOf(alice).Single().Status);
    }
}
