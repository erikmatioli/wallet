package br.com.walletcore.adapter.in.rest;

import br.com.walletcore.application.port.in.AccountControlUseCase;
import br.com.walletcore.application.port.out.AccountStatusEventRepository.CcsAccountEvent;
import br.com.walletcore.application.port.out.JudicialTransferRepository.PendingTransit;
import br.com.walletcore.domain.account.AccountBlock;
import br.com.walletcore.domain.account.AccountStatusEvent;
import br.com.walletcore.domain.account.Actor;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request and response bodies of the blocks, the closing, the judicial transfers and the CCS feed (ADR-013). */
public final class AccountControlModels {

    private AccountControlModels() {
    }

    @Schema(description = "Referência da ordem judicial.")
    public record CourtOrderBody(
            @Schema(description = "Número do processo", example = "0001234-56.2026.8.26.0100")
            @NotBlank @Size(max = 60) String process,
            @Schema(description = "Protocolo da ordem", example = "SISBAJUD 20261011000123") @Size(max = 60) String protocol) {

        AccountBlock.CourtOrder toDomain() {
            return new AccountBlock.CourtOrder(process, protocol);
        }

        static CourtOrderBody from(AccountBlock.CourtOrder c) {
            return c == null ? null : new CourtOrderBody(c.process(), c.protocol());
        }
    }

    @Schema(description = "Um bloqueio novo na conta.")
    public record BlockRequest(
            @Schema(description = "FRAUD, ADMINISTRATIVE ou JUDICIAL", example = "FRAUD") @NotNull AccountBlock.Type type,
            @Schema(description = "FRAUD_SUSPICION, CUSTOMER_REQUEST, REGISTRATION_PENDING, INSTITUTION_DECISION, COURT_ORDER ou OTHER",
                    example = "FRAUD_SUSPICION") @NotNull AccountBlock.Reason reason,
            @Schema(description = "Nome do operador (declarado; o console ainda não tem login de operador)", example = "maria.souza")
            @NotBlank @Size(max = 80) String operator,
            @Schema(description = "Obrigatório no bloqueio judicial") @Valid CourtOrderBody court,
            @Schema(description = "Observação; obrigatória no motivo OTHER", example = "Pix recebidos de contas recém-abertas")
            @Size(max = 280) String note) {
    }

    @Schema(description = "A liberação de um bloqueio.")
    public record ReleaseRequest(
            @Schema(description = "Nome do operador", example = "maria.souza") @NotBlank @Size(max = 80) String operator,
            @Schema(description = "Por que o bloqueio é liberado", example = "Análise concluída sem indício de fraude")
            @NotBlank @Size(max = 280) String note) {
    }

    @Schema(description = "Quem fez: o operador declarado e o client_id do token.")
    public record ActorBody(String operator, String clientId) {

        static ActorBody from(Actor a) {
            return a == null ? null : new ActorBody(a.operator(), a.clientId());
        }
    }

    @Schema(description = "Um bloqueio da conta, ativo ou liberado.")
    public record BlockResponse(
            UUID id, UUID accountId, String type, String reason, String note, CourtOrderBody court, ActorBody createdBy,
            Instant createdAt, boolean active, ActorBody releasedBy, Instant releasedAt, String releaseNote) {

        static BlockResponse from(AccountBlock b) {
            AccountBlock.Release r = b.release();
            return new BlockResponse(b.id().value(), b.accountId().value(), b.type().name(), b.reason().name(), b.note(),
                    CourtOrderBody.from(b.court()), ActorBody.from(b.createdBy()), b.createdAt(), b.isActive(),
                    r == null ? null : ActorBody.from(r.by()), r == null ? null : r.at(), r == null ? null : r.note());
        }
    }

    public record BlocksResponse(List<BlockResponse> items) {
    }

    @Schema(description = "O encerramento da conta.")
    public record CloseRequest(
            @Schema(description = "CUSTOMER_REQUEST, INSTITUTION_DECISION, DEATH ou OTHER", example = "CUSTOMER_REQUEST")
            @NotNull AccountStatusEvent.CloseReason reason,
            @Schema(description = "Nome do operador", example = "maria.souza") @NotBlank @Size(max = 80) String operator,
            @Schema(description = "Observação; obrigatória no motivo OTHER") @Size(max = 280) String note) {
    }

    @Schema(description = "Uma linha da linha do tempo da conta.")
    public record TimelineItemResponse(
            @Schema(description = "OPENED, CLOSED, BLOCKED ou RELEASED") String kind, Instant at, ActorBody actor,
            String reason, String note, UUID blockId, String blockType, CourtOrderBody court) {

        static TimelineItemResponse from(AccountControlUseCase.TimelineItem i) {
            AccountBlock b = i.block();
            return new TimelineItemResponse(i.kind().name(), i.at(), ActorBody.from(i.actor()), i.reason(), i.note(),
                    b == null ? null : b.id().value(), b == null ? null : b.type().name(),
                    b == null ? null : CourtOrderBody.from(b.court()));
        }
    }

    public record TimelineResponse(List<TimelineItemResponse> items) {
    }

    @Schema(description = "Para onde vai o dinheiro da ordem judicial.")
    public record JudicialDestinationBody(
            @Schema(description = "ACCOUNT (outro cliente do tenant) ou JUDICIAL_TRANSIT (depósito judicial em outro banco)",
                    example = "JUDICIAL_TRANSIT") @NotNull Kind kind,
            @Schema(description = "UUID da conta de destino (ACCOUNT, ou agência + número + dígito)") UUID accountId,
            @Pattern(regexp = "\\d{4}") String branch,
            @Pattern(regexp = "\\d{1,20}") String number,
            @Pattern(regexp = "\\d") String checkDigit) {

        public enum Kind { ACCOUNT, JUDICIAL_TRANSIT }
    }

    @Schema(description = "Uma transferência por ordem judicial, de uma conta com bloqueio judicial.")
    public record JudicialTransferRequest(
            @Schema(description = "O bloqueio judicial que a ordem cumpre") @NotNull UUID blockId,
            @Schema(description = "Valor, até o saldo", example = "1500.00")
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
            @NotNull @Valid JudicialDestinationBody destination,
            @Schema(description = "Nome do operador", example = "maria.souza") @NotBlank @Size(max = 80) String operator,
            @Schema(description = "Observação", example = "transferência para depósito judicial no Banco do Brasil")
            @Size(max = 280) String note) {
    }

    @Schema(description = "A TED de uma transferência judicial ao trânsito, enviada pela tesouraria.")
    public record JudicialTransitOutRequest(
            @Schema(description = "A conta de lastro de onde a TED saiu") @NotNull UUID backingAccountId,
            @Schema(description = "Valor da TED", example = "1500.00")
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
            @Schema(description = "Comprovante da TED", example = "TED 000987 / ID depósito 2026101100001")
            @NotBlank @Size(max = 140) String document,
            @Schema(description = "Nome do operador", example = "joao.lima") @NotBlank @Size(max = 80) String operator,
            @Size(max = 280) String note) {
    }

    @Schema(description = "Uma transferência judicial ao trânsito cuja TED ainda falta registrar.")
    public record PendingTransitResponse(UUID transferId, UUID accountId, String process, String protocol,
                                         BigDecimal amount, BigDecimal sent, BigDecimal remaining, Instant occurredAt) {

        static PendingTransitResponse from(PendingTransit p) {
            return new PendingTransitResponse(p.transferId().value(), p.accountId().value(), p.process(), p.protocol(),
                    p.amount().toDecimal(), p.sent().toDecimal(), p.remaining().toDecimal(), p.occurredAt());
        }
    }

    public record PendingTransitListResponse(List<PendingTransitResponse> items) {
    }

    @Schema(description = "Abertura ou encerramento de uma conta, com o que o CCS precisa.")
    public record CcsEventResponse(
            @Schema(description = "Posição no feed; o próximo after") long seq,
            UUID accountId,
            @Schema(description = "OPENED ou CLOSED") String event,
            Instant occurredAt,
            Holder holder,
            AccountNumber account) {

        public record Holder(String name, @Schema(description = "CPF ou CNPJ") String documentType, String document) {
        }

        public record AccountNumber(String ispb, String branch, String number, String checkDigit, String type) {
        }

        static CcsEventResponse from(CcsAccountEvent e) {
            return new CcsEventResponse(e.seq(), e.accountId().value(), e.kind().name(), e.occurredAt(),
                    new Holder(e.holderName(), e.taxIdType(), e.taxId()),
                    new AccountNumber(e.ispb(), e.branch(), e.number(), e.checkDigit(), e.accountType()));
        }
    }

    @Schema(description = "Uma página do feed do CCS.")
    public record CcsFeedResponse(List<CcsEventResponse> events,
                                  @Schema(description = "O after da próxima chamada") long next) {
    }
}
