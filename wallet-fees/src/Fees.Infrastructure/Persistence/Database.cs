using System.Reflection;
using DbUp;
using Fees.Application;
using Npgsql;

namespace Fees.Infrastructure.Persistence;

// The connection and transaction of one unit of work (a request, a message), shared by the repositories.
public sealed class DbSession(NpgsqlDataSource dataSource) : IAsyncDisposable
{
    private NpgsqlConnection? connection;

    public NpgsqlTransaction? Transaction { get; private set; }

    public NpgsqlConnection Connection =>
        connection ?? throw new InvalidOperationException("no unit of work: run inside ITransactionRunner");

    internal async Task BeginAsync(CancellationToken ct)
    {
        connection ??= await dataSource.OpenConnectionAsync(ct);
        Transaction = await connection.BeginTransactionAsync(ct);
    }

    internal async Task EndAsync(bool commit, CancellationToken ct)
    {
        if (Transaction is null)
        {
            return;
        }
        if (commit)
        {
            await Transaction.CommitAsync(ct);
        }
        else
        {
            await Transaction.RollbackAsync(ct);
        }
        await Transaction.DisposeAsync();
        Transaction = null;
    }

    public async ValueTask DisposeAsync()
    {
        if (Transaction is not null)
        {
            await Transaction.DisposeAsync();
        }
        if (connection is not null)
        {
            await connection.DisposeAsync();
        }
    }
}

// Opens a transaction for the work unless one is already open: a use case calling another joins its transaction.
public sealed class TransactionRunner(DbSession session) : ITransactionRunner
{
    public async Task<T> InTransactionAsync<T>(Func<Task<T>> work, CancellationToken ct = default)
    {
        if (session.Transaction is not null)
        {
            return await work();
        }
        await session.BeginAsync(ct);
        try
        {
            var result = await work();
            await session.EndAsync(commit: true, ct);
            return result;
        }
        catch
        {
            await session.EndAsync(commit: false, CancellationToken.None);
            throw;
        }
    }
}

// The schema: the SQL scripts in Migrations/, applied once each and in order, with the owner role (ADR-001).
public static class Migrator
{
    public static void Migrate(string ownerConnectionString)
    {
        var result = DeployChanges.To
            .PostgresqlDatabase(ownerConnectionString)
            .WithScriptsEmbeddedInAssembly(Assembly.GetExecutingAssembly())
            .WithTransactionPerScript()
            .LogToConsole()
            .Build()
            .PerformUpgrade();
        if (!result.Successful)
        {
            throw new InvalidOperationException("database migration failed", result.Error);
        }
    }
}
