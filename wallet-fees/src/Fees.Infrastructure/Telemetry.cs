using System.Diagnostics;

namespace Fees.Infrastructure;

// The spans this service opens itself: a message read from the queue, a run of the monthly fees. The HTTP requests,
// the calls to the core and the queries come from the instrumentations (Program.cs).
public static class Telemetry
{
    public const string SourceName = "Wallet.Fees";

    public static readonly ActivitySource Source = new(SourceName);
}
