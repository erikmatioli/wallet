package br.com.walletcore.adapter.in.rest;

import br.com.walletcore.application.port.in.TreasuryUseCase;
import br.com.walletcore.domain.backing.BackingAccount;
import br.com.walletcore.domain.backing.TreasuryReference;
import br.com.walletcore.domain.ledger.LedgerEntry;
import br.com.walletcore.domain.shared.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Contracts of the treasury API (ADR-011). Every amount of a backing account is its "expected balance": what the
 * tenant should have in that place, positive (the ledger keeps it with the opposite sign).
 */
public final class TreasuryModels {

    private TreasuryModels() {
    }

    @Schema(description = "Cadastro de uma conta de lastro pela tesouraria.")
    public record RegisterBackingAccountRequest(
            @Schema(description = "RESERVES, CCME, INVESTMENT ou CREDIT_PORTFOLIO (Conta PI, Caixa e A regularizar já existem)",
                    example = "INVESTMENT") @NotBlank String type,
            @Schema(description = "Nome", example = "CDB Banco X", maxLength = 80) @NotBlank @Size(max = 80) String name,
            @Schema(description = "Onde ela está fora do ledger (instituição, agência e conta)", example = "341 / 0001 / 12345-6",
                    maxLength = 140) @Size(max = 140) String externalRef) {
    }

    @Schema(description = "Comprovante e observação de uma movimentação de tesouraria.")
    public record ReferenceDto(
            @Schema(description = "Comprovante", example = "TED 000123", maxLength = 140) @Size(max = 140) String document,
            @Schema(description = "Observação", example = "resgate do CDB, cobertura dos créditos de 09/10", maxLength = 280)
            @Size(max = 280) String note) {

        static ReferenceDto from(TreasuryReference r) {
            return r == null ? null : new ReferenceDto(r.document(), r.note());
        }
    }

    @Schema(description = "Movimentação de tesouraria entre duas contas de lastro: cobertura (saindo de A regularizar) ou recomposição.")
    public record TreasuryTransferRequest(
            @Schema(description = "Conta de lastro de onde o valor sai") @NotNull UUID from,
            @Schema(description = "Conta de lastro onde o valor está agora") @NotNull UUID to,
            @Schema(description = "Valor", example = "223.76", minimum = "0.01") @NotNull @DecimalMin("0.01") BigDecimal amount,
            @Schema(description = "Descrição; vazia, o core escreve \"Cobertura: A regularizar → Reservas\"", maxLength = 140)
            @Size(max = 140) String description,
            @Schema(description = "Comprovante e observação") @Valid ReferenceDto reference) {
    }

    @Schema(description = "Uma conta de lastro.")
    public record BackingAccountResponse(
            @Schema(description = "UUID da conta de lastro") UUID id,
            @Schema(description = "PIX_PI, RESERVES, CCME, INVESTMENT, CASH, CREDIT_PORTFOLIO ou UNASSIGNED", example = "PIX_PI") String type,
            @Schema(description = "Nome", example = "Conta PI") String name,
            @Schema(description = "Onde ela está fora do ledger") String externalRef,
            @Schema(description = "Criada em") Instant createdAt) {

        static BackingAccountResponse from(BackingAccount b) {
            return new BackingAccountResponse(b.id().value(), b.type().name(), b.name(), b.externalRef(), b.createdAt());
        }
    }

    @Schema(description = "Uma conta de lastro com o saldo esperado.")
    public record PositionResponse(
            @Schema(description = "A conta de lastro") BackingAccountResponse account,
            @Schema(description = "Saldo esperado agora (o que o tenant deveria ter ali)", example = "199.10") BigDecimal expected,
            @Schema(description = "Saldo esperado no fim de ontem; vazio no primeiro minuto do dia", example = "216.10")
            BigDecimal expectedYesterday,
            @Schema(description = "Moeda", example = "BRL") String currency) {

        static PositionResponse from(TreasuryUseCase.Position p) {
            return new PositionResponse(BackingAccountResponse.from(p.account()), p.expected().toDecimal(),
                    p.expectedYesterday() == null ? null : p.expectedYesterday().toDecimal(), Money.CURRENCY);
        }
    }

    @Schema(description = "As contas de lastro do tenant. A soma dos saldos esperados é a posição do tenant: a soma dos saldos dos clientes.")
    public record PositionsResponse(
            @Schema(description = "Contas de lastro") List<PositionResponse> accounts,
            @Schema(description = "Posição do tenant: soma dos saldos esperados", example = "422.86") BigDecimal total,
            @Schema(description = "Moeda", example = "BRL") String currency) {

        static PositionsResponse from(List<TreasuryUseCase.Position> positions) {
            Money total = positions.stream().map(TreasuryUseCase.Position::expected).reduce(Money.ZERO, Money::plus);
            return new PositionsResponse(positions.stream().map(PositionResponse::from).toList(), total.toDecimal(),
                    Money.CURRENCY);
        }
    }

    @Schema(description = "Saldo esperado no fim de cada dia.")
    public record DailyPositionsResponse(
            @Schema(description = "UUID da conta de lastro") UUID backingAccountId,
            @Schema(description = "Moeda", example = "BRL") String currency,
            @Schema(description = "Um dia por item, do mais antigo para o mais novo") List<DayExpected> days) {

        static DailyPositionsResponse from(UUID id, List<TreasuryUseCase.DayPosition> days) {
            return new DailyPositionsResponse(id, Money.CURRENCY,
                    days.stream().map(d -> new DayExpected(d.date(), d.expected().toDecimal())).toList());
        }
    }

    public record DayExpected(
            @Schema(description = "Dia", example = "2026-10-09") LocalDate date,
            @Schema(description = "Saldo esperado no fim do dia", example = "199.10") BigDecimal expected) {
    }

    @Schema(description = "Um lançamento de uma conta de lastro.")
    public record BackingEntryResponse(
            @Schema(description = "UUID da transação") UUID transactionId,
            @Schema(description = "Tipo da transação", example = "DEPOSIT") String type,
            @Schema(description = "IN: o dinheiro chegou a este lugar; OUT: saiu dele", example = "IN") String direction,
            @Schema(description = "Valor", example = "100.00") BigDecimal amount,
            @Schema(description = "Saldo esperado da conta de lastro logo depois deste lançamento", example = "223.76")
            BigDecimal expectedAfter,
            @Schema(description = "Descrição") String description,
            @Schema(description = "Data e hora") Instant occurredAt,
            @Schema(description = "Cliente do movimento (movimentos de cliente)", example = "Maria Oliveira") String customerName,
            @Schema(description = "Conta do cliente", example = "0001 / 00100002-9") String customerAccount,
            @Schema(description = "A outra conta de lastro (movimentação de tesouraria)") BackingAccountResponse otherBackingAccount,
            @Schema(description = "Comprovante e observação (movimentação de tesouraria)") ReferenceDto reference) {

        static BackingEntryResponse from(TreasuryUseCase.Entry e) {
            LedgerEntry le = e.entry();
            return new BackingEntryResponse(le.transactionId().value(), le.type().name(), e.in() ? "IN" : "OUT",
                    le.amount().toDecimal(), e.expectedAfter().toDecimal(), le.description(), le.occurredAt(),
                    e.customerName(), e.customerAccount(),
                    e.otherBackingAccount() == null ? null : BackingAccountResponse.from(e.otherBackingAccount()),
                    ReferenceDto.from(e.reference()));
        }
    }

    @Schema(description = "Os lançamentos de uma conta de lastro, do mais novo para o mais antigo.")
    public record BackingStatementResponse(
            @Schema(description = "A conta de lastro") BackingAccountResponse account,
            @Schema(description = "Lançamentos") List<BackingEntryResponse> entries,
            @Schema(description = "Cursor da próxima página (vazio quando não há)", example = "18.1.7") String next,
            @Schema(description = "Moeda", example = "BRL") String currency) {

        static BackingStatementResponse from(TreasuryUseCase.Statement s) {
            return new BackingStatementResponse(BackingAccountResponse.from(s.account()),
                    s.entries().stream().map(BackingEntryResponse::from).toList(), s.next(), Money.CURRENCY);
        }
    }

    @Schema(description = "O dinheiro descoberto dia a dia: criado por depósitos, coberto pela tesouraria e o que sobrou no fim do dia.")
    public record UncoveredDailyResponse(
            @Schema(description = "Moeda", example = "BRL") String currency,
            @Schema(description = "Um dia por item, do mais antigo para o mais novo") List<UncoveredDayDto> days) {

        static UncoveredDailyResponse from(List<TreasuryUseCase.UncoveredDay> days) {
            return new UncoveredDailyResponse(Money.CURRENCY, days.stream().map(d -> new UncoveredDayDto(d.date(),
                    d.created().toDecimal(), d.covered().toDecimal(), d.uncoveredAtEnd().toDecimal())).toList());
        }
    }

    public record UncoveredDayDto(
            @Schema(description = "Dia", example = "2026-10-09") LocalDate date,
            @Schema(description = "Criado no dia (depósitos)", example = "223.76") BigDecimal created,
            @Schema(description = "Coberto no dia", example = "0.00") BigDecimal covered,
            @Schema(description = "Descoberto no fim do dia", example = "223.76") BigDecimal uncoveredAtEnd) {
    }
}
