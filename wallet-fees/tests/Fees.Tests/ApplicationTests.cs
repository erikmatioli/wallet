using Fees.Application;
using Fees.Domain;

namespace Fees.Tests;

// Packages, schedules and accounts through the use cases, with the ports in memory.
public class ApplicationTests
{
    private readonly Fixture f = new();

    private static IReadOnlyList<OperationPrice> Prices(long pixOut) =>
        [new OperationPrice(Operation.PIX_OUT, 0, pixOut, null)];

    [Fact]
    public async Task ATenantStartsWithTheSixPackagesOnce()
    {
        await f.PackageService.EnsureDefaultsAsync(f.Tenant);
        await f.PackageService.EnsureDefaultsAsync(f.Tenant);

        var packages = await f.PackageService.ListAsync(f.Tenant);
        Assert.Equal(["PF_BASIC", "PF_STANDARD", "PF_VIP", "PJ_BASIC", "PJ_STANDARD", "PJ_VIP"],
            packages.Select(p => p.Package.Code).Order().ToArray());
        Assert.Equal(2, packages.Count(p => p.Package.IsDefault));
        // The example of ADR-001, in force from the first day.
        var basic = packages.Single(p => p.Package.Code == "PF_BASIC").Current!;
        Assert.Equal(0, basic.MonthlyFeeCents);
        Assert.Equal(100, basic.PriceOf(Operation.WITHDRAWAL).PriceCents);
        Assert.Equal(2, basic.PriceOf(Operation.TRANSFER_OUT).FreePerMonth);
        Assert.Equal(150, basic.PriceOf(Operation.PIX_OUT).PriceCents);
    }

    [Fact]
    public async Task ANewPriceStartsOnADateAndTheOldOneStays()
    {
        await f.PackageService.EnsureDefaultsAsync(f.Tenant);
        var basic = f.PackageOf("PF_BASIC");

        await f.PackageService.AddScheduleAsync(f.Tenant, basic.Id,
            new ScheduleCommand(f.Today.AddDays(10), 990, Prices(200), "maria"));

        var view = (await f.PackageService.ListAsync(f.Tenant)).Single(p => p.Package.Id == basic.Id);
        Assert.Equal(150, view.Current!.PriceOf(Operation.PIX_OUT).PriceCents);
        Assert.Equal(200, view.Next!.PriceOf(Operation.PIX_OUT).PriceCents);
        Assert.Equal(2, (await f.PackageService.SchedulesAsync(f.Tenant, basic.Id)).Count);
    }

    [Fact]
    public async Task AScheduleNotInForceYetCanBeReplacedButThePastCannot()
    {
        await f.PackageService.EnsureDefaultsAsync(f.Tenant);
        var basic = f.PackageOf("PF_BASIC");
        var nextWeek = f.Today.AddDays(7);
        await f.PackageService.AddScheduleAsync(f.Tenant, basic.Id, new ScheduleCommand(nextWeek, 0, Prices(200), "maria"));

        await f.PackageService.AddScheduleAsync(f.Tenant, basic.Id, new ScheduleCommand(nextWeek, 0, Prices(250), "joao"));

        var schedules = await f.PackageService.SchedulesAsync(f.Tenant, basic.Id);
        Assert.Equal(250, schedules.Single(s => s.ValidFrom == nextWeek).PriceOf(Operation.PIX_OUT).PriceCents);
        Assert.Equal("PAST_DATE", (await Assert.ThrowsAsync<FeeValidationException>(() =>
            f.PackageService.AddScheduleAsync(f.Tenant, basic.Id,
                new ScheduleCommand(f.Today.AddDays(-1), 0, Prices(1), "maria")))).Code);
        Assert.Equal("SCHEDULE_IN_FORCE", (await Assert.ThrowsAsync<FeeRuleException>(() =>
            f.PackageService.AddScheduleAsync(f.Tenant, basic.Id,
                new ScheduleCommand(f.Today, 0, Prices(1), "maria")))).Code);
        Assert.Equal("PACKAGE_NOT_FOUND", (await Assert.ThrowsAsync<FeeNotFoundException>(() =>
            f.PackageService.AddScheduleAsync(f.Tenant, Guid.CreateVersion7(),
                new ScheduleCommand(nextWeek, 0, Prices(1), "maria")))).Code);
    }

    [Fact]
    public async Task ANewAccountStartsInTheBasicPackageOfItsTypeOnce()
    {
        var person = Guid.CreateVersion7();
        var company = Guid.CreateVersion7();
        var eventId = Guid.CreateVersion7();

        await f.AccountService.OnOpenedAsync(eventId, f.Tenant, person, "CPF", f.Clock.Now);
        await f.AccountService.OnOpenedAsync(eventId, f.Tenant, person, "CPF", f.Clock.Now); // delivered twice
        await f.AccountService.OnOpenedAsync(Guid.CreateVersion7(), f.Tenant, company, "CNPJ", f.Clock.Now);

        Assert.Equal(f.PackageOf("PF_BASIC").Id, f.Accounts[person].PackageId);
        Assert.Equal(f.PackageOf("PJ_BASIC").Id, f.Accounts[company].PackageId);
        Assert.Single(f.Changes, c => c.AccountId == person);
    }

    [Fact]
    public async Task TheOperatorMovesAnAccountAndTheChangeIsKept()
    {
        var account = Guid.CreateVersion7();
        await f.AccountService.OnOpenedAsync(Guid.CreateVersion7(), f.Tenant, account, "CPF", f.Clock.Now);
        f.Clock.Now = f.Clock.Now.AddHours(2);

        var view = await f.AccountService.ChangePackageAsync(f.Tenant, account, f.PackageOf("PF_VIP").Id, "maria",
            "demo-tenant");

        Assert.Equal("PF_VIP", view.Package.Code);
        Assert.Equal(f.Clock.Now, view.Account.Since);
        Assert.Equal(["maria", "sistema"], view.Changes.Select(c => c.Operator).ToArray());
        Assert.Equal("PACKAGE_OF_ANOTHER_TYPE", (await Assert.ThrowsAsync<FeeRuleException>(() =>
            f.AccountService.ChangePackageAsync(f.Tenant, account, f.PackageOf("PJ_VIP").Id, "maria", "demo-tenant"))).Code);
        Assert.Equal("ACCOUNT_NOT_FOUND", (await Assert.ThrowsAsync<FeeNotFoundException>(() =>
            f.AccountService.GetAsync(f.Tenant, Guid.CreateVersion7()))).Code);
    }

    [Fact]
    public async Task TheSyncBringsOnlyTheAccountsTheBasketHasNotSeen()
    {
        var known = Guid.CreateVersion7();
        await f.AccountService.OnOpenedAsync(Guid.CreateVersion7(), f.Tenant, known, "CPF", f.Clock.Now);
        var closed = Guid.CreateVersion7();

        var added = await f.AccountService.SyncAsync(f.Tenant,
        [
            new CoreAccount(known, "CPF", "ACTIVE"),
            new CoreAccount(Guid.CreateVersion7(), "CNPJ", "ACTIVE"),
            new CoreAccount(closed, "CPF", "CLOSED"),
        ]);

        Assert.Equal(2, added);
        Assert.Equal(AccountStatus.CLOSED, f.Accounts[closed].Status);
    }

    [Fact]
    public async Task AClosedAccountIsMarkedAndAnUnknownOneIgnored()
    {
        var account = Guid.CreateVersion7();
        await f.AccountService.OnOpenedAsync(Guid.CreateVersion7(), f.Tenant, account, "CPF", f.Clock.Now);

        await f.AccountService.OnClosedAsync(Guid.CreateVersion7(), f.Tenant, account);
        await f.AccountService.OnClosedAsync(Guid.CreateVersion7(), f.Tenant, Guid.CreateVersion7());

        Assert.Equal(AccountStatus.CLOSED, f.Accounts[account].Status);
        Assert.Single(f.Accounts);
    }
}
