package br.com.walletcore.application.service;

import br.com.walletcore.application.port.in.AuditLedgerUseCase;
import br.com.walletcore.application.port.out.AccountRepository;
import br.com.walletcore.application.port.out.LedgerRepository;
import br.com.walletcore.application.port.out.MetricsRecorder;
import br.com.walletcore.application.port.out.TransactionRunner;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.exception.NotFoundException;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TenantId;
import java.util.ArrayList;
import java.util.List;

/**
 * Event-sourcing style replay: folds all ledger entries of an account from sequence 1 and
 * verifies (a) the sequence is gapless, (b) each entry's running balance matches, (c) the final
 * balance and version equal the stored projection.
 */
public final class AuditLedgerService implements AuditLedgerUseCase {

    private static final int MAX_FINDINGS = 20;

    private final TransactionRunner tx;
    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final MetricsRecorder metrics;

    public AuditLedgerService(TransactionRunner tx, AccountRepository accounts, LedgerRepository ledger,
                              MetricsRecorder metrics) {
        this.tx = tx;
        this.accounts = accounts;
        this.ledger = ledger;
        this.metrics = metrics;
    }

    @Override
    public AuditReport audit(TenantId tenantId, AccountId accountId) {
        return tx.readOnly(tenantId, () -> { // one REPEATABLE READ snapshot for account + entries
            Account account = accounts.findById(tenantId, accountId)
                    .filter(a -> a.kind() == Account.Kind.CUSTOMER)
                    .orElseThrow(() -> new NotFoundException("ACCOUNT_NOT_FOUND", "account not found"));

            List<String> findings = new ArrayList<>();
            long[] state = {0L, 1L, 0L}; // running balance, expected next sequence, entry count
            ledger.forEachInSequence(tenantId, accountId, entry -> {
                if (entry.sequence() != state[1] && findings.size() < MAX_FINDINGS) {
                    findings.add("sequence gap: expected " + state[1] + " but found " + entry.sequence());
                }
                state[0] += entry.signedCents();
                if (state[0] != entry.balanceAfter().cents() && findings.size() < MAX_FINDINGS) {
                    findings.add("balance mismatch at sequence " + entry.sequence() + ": replayed "
                            + state[0] + " but entry says " + entry.balanceAfter().cents());
                }
                state[1] = entry.sequence() + 1;
                state[2]++;
            });

            Money replayed = Money.ofCents(state[0]);
            long lastSequence = state[1] - 1;
            if (!replayed.equals(account.balance())) {
                findings.add("stored balance " + account.balance().cents() + " differs from replayed balance "
                        + replayed.cents());
            }
            if (lastSequence != account.version()) {
                findings.add("stored version " + account.version() + " differs from last sequence " + lastSequence);
            }
            boolean consistent = findings.isEmpty();
            metrics.auditCompleted(tenantId, consistent, findings.size());
            return new AuditReport(accountId, state[2], account.balance(), replayed, account.version(),
                    consistent, List.copyOf(findings));
        });
    }
}
