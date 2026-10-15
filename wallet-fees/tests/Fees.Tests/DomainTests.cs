using Fees.Domain;

namespace Fees.Tests;

// The rules of a schedule and of a package change, without any infrastructure.
public class DomainTests
{
    private static readonly Guid Tenant = Guid.CreateVersion7();
    private static readonly DateTimeOffset Now = new(2026, 10, 15, 12, 0, 0, TimeSpan.Zero);

    private static FeeSchedule Schedule(params OperationPrice[] prices) =>
        FeeSchedule.Create(Tenant, Guid.CreateVersion7(), new DateOnly(2026, 10, 15), 0, prices, "maria", Now);

    [Fact]
    public void APixReceivedIsPricedByWhoPaidIt()
    {
        var schedule = Schedule(new OperationPrice(Operation.PIX_IN, 0, 50, 200));

        Assert.Equal(50, schedule.PriceOf(Operation.PIX_IN).PriceFor(CustomerType.PF));
        Assert.Equal(200, schedule.PriceOf(Operation.PIX_IN).PriceFor(CustomerType.PJ));
        // An operation the schedule does not list is free.
        Assert.Equal(0, schedule.PriceOf(Operation.WITHDRAWAL).PriceFor(null));
    }

    [Fact]
    public void AScheduleRefusesWhatCannotBeCharged()
    {
        Assert.Equal("INVALID_PRICE", Assert.Throws<FeeValidationException>(() =>
            Schedule(new OperationPrice(Operation.PIX_OUT, 0, -1, null))).Code);
        Assert.Equal("INVALID_FREE_QUOTA", Assert.Throws<FeeValidationException>(() =>
            Schedule(new OperationPrice(Operation.PIX_OUT, -1, 100, null))).Code);
        Assert.Equal("DUPLICATE_OPERATION", Assert.Throws<FeeValidationException>(() =>
            Schedule(new OperationPrice(Operation.PIX_OUT, 0, 100, null),
                new OperationPrice(Operation.PIX_OUT, 1, 100, null))).Code);
        // Only a Pix received has a price by who paid it.
        Assert.Equal("INVALID_PRICE", Assert.Throws<FeeValidationException>(() =>
            Schedule(new OperationPrice(Operation.WITHDRAWAL, 0, 100, 300))).Code);
        Assert.Equal("INVALID_OPERATOR", Assert.Throws<FeeValidationException>(() =>
            FeeSchedule.Create(Tenant, Guid.CreateVersion7(), new DateOnly(2026, 10, 15), 0, [], " ", Now)).Code);
    }

    [Fact]
    public void TheScheduleInForceIsTheLatestThatStartedOnTheDayOrBefore()
    {
        var package = Guid.CreateVersion7();
        var october = FeeSchedule.Create(Tenant, package, new DateOnly(2026, 10, 1), 0, [], "maria", Now);
        var november = FeeSchedule.Create(Tenant, package, new DateOnly(2026, 11, 1), 990, [], "maria", Now);

        Assert.Null(FeeSchedule.InForce([october, november], new DateOnly(2026, 9, 30)));
        Assert.Equal(october, FeeSchedule.InForce([october, november], new DateOnly(2026, 10, 31)));
        Assert.Equal(november, FeeSchedule.InForce([october, november], new DateOnly(2026, 11, 1)));
    }

    [Fact]
    public void AnAccountOnlyMovesToAnotherPackageOfItsType()
    {
        var basic = new Package(Guid.CreateVersion7(), Tenant, "PF_BASIC", "Basic", CustomerType.PF, true, Now);
        var vip = basic with { Id = Guid.CreateVersion7(), Code = "PF_VIP", IsDefault = false };
        var company = basic with { Id = Guid.CreateVersion7(), Code = "PJ_VIP", CustomerType = CustomerType.PJ };
        var account = new FeeAccount(Tenant, Guid.CreateVersion7(), CustomerType.PF, basic.Id, AccountStatus.ACTIVE, Now);

        Assert.Equal(vip.Id, account.MoveTo(vip, Now.AddDays(1)).PackageId);
        Assert.Equal("PACKAGE_OF_ANOTHER_TYPE", Assert.Throws<FeeRuleException>(() => account.MoveTo(company, Now)).Code);
        Assert.Equal("SAME_PACKAGE", Assert.Throws<FeeRuleException>(() => account.MoveTo(basic, Now)).Code);
        Assert.Equal("ACCOUNT_CLOSED", Assert.Throws<FeeRuleException>(() =>
            (account with { Status = AccountStatus.CLOSED }).MoveTo(vip, Now)).Code);
    }

    [Fact]
    public void TheDayIsBrasiliasNotUtcs()
    {
        // 02:30 UTC on the 16th is still 23:30 of the 15th in Brasília.
        Assert.Equal(new DateOnly(2026, 10, 15), Brasilia.DayOf(new DateTimeOffset(2026, 10, 16, 2, 30, 0, TimeSpan.Zero)));
    }
}
