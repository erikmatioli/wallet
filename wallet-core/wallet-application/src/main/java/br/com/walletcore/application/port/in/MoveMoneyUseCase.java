package br.com.walletcore.application.port.in;

import br.com.walletcore.domain.ledger.TransactionType;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TenantId;
import br.com.walletcore.domain.shared.TransactionId;
import java.time.Instant;

/**
 * Money movements. Every command carries an idempotency key: repeating a request with the same
 * key and payload returns the original result without moving money twice.
 */
public interface MoveMoneyUseCase {

    TransactionResult deposit(DepositCommand command);

    TransactionResult withdraw(WithdrawCommand command);

    TransactionResult transfer(TransferCommand command);

    record DepositCommand(TenantId tenantId, AccountId accountId, Money amount, String description,
                          String idempotencyKey) {
    }

    record WithdrawCommand(TenantId tenantId, AccountId accountId, Money amount, String description,
                           String idempotencyKey) {
    }

    sealed interface Destination {
        record ById(AccountId id) implements Destination {
        }

        record ByNumber(String branch, String number, String checkDigit) implements Destination {
        }
    }

    record TransferCommand(TenantId tenantId, AccountId source, Destination destination, Money amount,
                           String description, String idempotencyKey) {
    }

    record TransactionResult(TransactionId id, TransactionType type, Money amount, Instant occurredAt,
                             String description, boolean replayed) {
    }
}
