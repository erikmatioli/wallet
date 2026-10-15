using Fees.Application;
using Fees.Domain;

namespace Fees.Tests;

// The monthly fee (ADR-001, decision 7), with the ports in memory.
public class MonthlyFeeTests
{
    private readonly Fixture f = new();

    private async Task<Guid> OpenAsync(string documentType, long balanceCents)
    {
        var account = Guid.CreateVersion7();
        await f.AccountService.OnOpenedAsync(Guid.CreateVersion7(), f.Tenant, account, documentType, f.Clock.Now);
        f.Core.Balances[account] = balanceCents;
        return account;
    }

    private async Task ChargeAsync()
    {
        foreach (var (tenant, account) in await f.MonthlyFeeService.RunAsync())
        {
            await f.ChargeService.ChargePendingAsync(tenant, account);
        }
    }

    // A monthly fee for the PF standard package from November on.
    private async Task<Package> StandardWithMonthlyFeeAsync(long cents)
    {
        var standard = f.PackageOf("PF_STANDARD");
        await f.PackageService.AddScheduleAsync(f.Tenant, standard.Id,
            new ScheduleCommand(new DateOnly(2026, 11, 1), cents, [], "maria"));
        return standard;
    }

    [Fact]
    public async Task OnTheFirstEachAccountPaysThePackageInForceThatDayOnce()
    {
        var alice = await OpenAsync("CPF", 5_000);
        var bob = await OpenAsync("CPF", 5_000); // stays in basic: no monthly fee
        var standard = await StandardWithMonthlyFeeAsync(990);
        await f.AccountService.ChangePackageAsync(f.Tenant, alice, standard.Id, "maria", "console");

        await ChargeAsync(); // October: the service's first month, nothing to pay yet
        Assert.Empty(f.Charges);

        f.Clock.Now = new DateTimeOffset(2026, 11, 1, 3, 2, 0, TimeSpan.Zero); // 00:02 in Brasília: not yet
        await ChargeAsync();
        Assert.Empty(f.Charges);

        f.Clock.Now = new DateTimeOffset(2026, 11, 1, 3, 10, 0, TimeSpan.Zero); // 00:10 in Brasília
        await ChargeAsync();
        await ChargeAsync();

        var fee = Assert.Single(f.Charges);
        Assert.Equal((alice, 990L, ChargeStatus.CHARGED), (fee.AccountId, fee.AmountCents, fee.Status));
        Assert.Equal(("MONTHLY", "2026-11"), (fee.Operation, fee.Reference));
        Assert.Equal($"fee:monthly:{alice}:2026-11", fee.IdempotencyKey);
        Assert.Equal("Tarifa: mensalidade Standard 11/2026", fee.Description);
        Assert.Equal(5_000, f.Core.Balances[bob]);
    }

    [Fact]
    public async Task TheMonthsMissedAreChargedWhenTheServiceReturns()
    {
        var alice = await OpenAsync("CPF", 5_000);
        var standard = await StandardWithMonthlyFeeAsync(990);
        await f.AccountService.ChangePackageAsync(f.Tenant, alice, standard.Id, "maria", "console");
        await ChargeAsync(); // October

        f.Clock.Now = new DateTimeOffset(2027, 1, 20, 12, 0, 0, TimeSpan.Zero); // down since October
        await ChargeAsync();

        Assert.Equal(["2026-11", "2026-12", "2027-01"], f.Charges.Select(c => c.Reference!).ToArray());
        Assert.Equal(5_000 - 3 * 990, f.Core.Balances[alice]);
    }

    [Fact]
    public async Task WithoutBalanceTheMonthlyFeeWaitsLikeAnyOther()
    {
        var alice = await OpenAsync("CPF", 100);
        var standard = await StandardWithMonthlyFeeAsync(990);
        await f.AccountService.ChangePackageAsync(f.Tenant, alice, standard.Id, "maria", "console");
        await ChargeAsync();

        f.Clock.Now = new DateTimeOffset(2026, 11, 2, 12, 0, 0, TimeSpan.Zero);
        await ChargeAsync();

        Assert.Equal(ChargeStatus.PENDING, f.Charges.Single().Status);
        Assert.Contains(alice, f.Core.FeeBlocks);
    }

    [Fact]
    public async Task AClosedAccountPaysNoMonthlyFee()
    {
        var alice = await OpenAsync("CPF", 5_000);
        var standard = await StandardWithMonthlyFeeAsync(990);
        await f.AccountService.ChangePackageAsync(f.Tenant, alice, standard.Id, "maria", "console");
        await ChargeAsync();
        await f.AccountService.OnClosedAsync(Guid.CreateVersion7(), f.Tenant, alice);

        f.Clock.Now = new DateTimeOffset(2026, 11, 2, 12, 0, 0, TimeSpan.Zero);
        await ChargeAsync();

        Assert.Empty(f.Charges);
    }
}
