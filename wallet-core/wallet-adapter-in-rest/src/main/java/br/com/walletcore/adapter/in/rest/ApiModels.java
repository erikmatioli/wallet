package br.com.walletcore.adapter.in.rest;

import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.ledger.LedgerEntry;
import br.com.walletcore.domain.shared.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request/response contracts of the public API. Amounts are decimal BRL values with 2 places. */
public final class ApiModels {

    private ApiModels() {
    }

    // ---------------------------------------------------------------- requests
    public record OnboardCustomerRequest(
            @NotBlank @Size(max = 140) String name,
            @NotBlank @Size(max = 32) String taxId,
            @Size(max = 64) String externalRef) {
    }

    public record MoneyMovementRequest(
            @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
            @Size(max = 140) String description) {
    }

    public record DestinationNumber(
            @NotBlank @Pattern(regexp = "\\d{4}") String branch,
            @NotBlank @Pattern(regexp = "\\d{1,20}") String number,
            @NotBlank @Pattern(regexp = "\\d") String checkDigit) {
    }

    /** Provide exactly one of destinationAccountId or destination. */
    public record TransferRequest(
            @NotNull UUID sourceAccountId,
            UUID destinationAccountId,
            @Valid DestinationNumber destination,
            @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
            @Size(max = 140) String description) {
    }

    // ---------------------------------------------------------------- responses
    public record AccountResponse(
            UUID id,
            String ispb,
            String branch,
            String number,
            String checkDigit,
            String formatted,
            String type,
            String status,
            BigDecimal balance,
            String currency) {

        static AccountResponse from(Account a) {
            return new AccountResponse(
                    a.id().value(),
                    a.number().ispb(),
                    a.number().branch(),
                    a.number().number(),
                    a.number().checkDigit(),
                    a.number().formatted(),
                    a.number().type().bcbCode(),
                    a.status().name(),
                    a.balance().toDecimal(),
                    Money.CURRENCY);
        }
    }

    public record CustomerResponse(UUID id, String name, String documentType, String externalRef, String status) {
    }

    public record OnboardCustomerResponse(CustomerResponse customer, AccountResponse account) {
    }

    public record TransactionResponse(UUID id, String type, BigDecimal amount, String currency, String description,
                                      Instant occurredAt, boolean replayed) {
    }

    public record EntryResponse(UUID transactionId, long sequence, String type, String direction, BigDecimal amount,
                                BigDecimal balanceAfter, String description, Instant occurredAt) {

        static EntryResponse from(LedgerEntry e) {
            return new EntryResponse(e.transactionId().value(), e.sequence(), e.type().name(), e.direction().name(),
                    e.amount().toDecimal(), e.balanceAfter().toDecimal(), e.description(), e.occurredAt());
        }
    }

    public record StatementResponse(List<EntryResponse> entries, Long nextBefore) {
    }

    public record BalanceResponse(UUID accountId, BigDecimal balance, String currency) {
    }

    public record AuditResponse(UUID accountId, long entryCount, BigDecimal storedBalance, BigDecimal replayedBalance,
                                long version, boolean consistent, List<String> findings) {
    }
}
