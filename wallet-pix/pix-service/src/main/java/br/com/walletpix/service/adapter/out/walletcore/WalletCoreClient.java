package br.com.walletpix.service.adapter.out.walletcore;

import br.com.walletpix.service.application.MessageFailures.PermanentFailure;
import br.com.walletpix.service.application.MessageFailures.RetryLater;
import br.com.walletpix.service.application.port.PixPorts.Counterparty;
import br.com.walletpix.service.application.port.PixPorts.DebitResult;
import br.com.walletpix.service.application.port.PixPorts.PayerAccount;
import br.com.walletpix.service.application.port.PixPorts.PixRecord;
import br.com.walletpix.service.application.port.PixPorts.WalletCore;
import br.com.walletpix.service.config.PixProperties;
import br.com.walletpix.service.domain.Amounts;
import br.com.walletpix.service.domain.HolderCheckResult;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * wallet-core over HTTP, as the tenant that owns the ISPB in question - so Row Level Security
 * still scopes every call to that tenant's accounts. Three tokens per tenant, least privilege per
 * use: the full one for reads, one down-scoped to {@code pix:receive} for the credits of a Pix or a
 * return received, and one down-scoped to {@code pix:send} for the debit and its reversal. Every
 * money movement is a Pix type in wallet-core, with the Pix detail (ADR-010 of wallet-core).
 *
 * <p>Error mapping: network failures, 5xx and 429 are {@link RetryLater} (the message stays on
 * the queue); a 401 drops the cached token and retries once; any other 4xx is
 * {@link PermanentFailure} - e.g. a credit refused because the account was blocked after we
 * accepted the Pix, which needs a human, not a retry loop.
 */
@Component
class WalletCoreClient implements WalletCore {

    private static final Logger log = LoggerFactory.getLogger(WalletCoreClient.class);
    /** Renew the token this long before wallet-core says it expires. */
    private static final long EXPIRY_MARGIN_SECONDS = 60;
    /** Scope of the debit/reversal token (null elsewhere = every scope of the tenant). */
    private static final String SEND_SCOPE = "pix:send";
    /** Scope of the credit token. */
    private static final String RECEIVE_SCOPE = "pix:receive";
    private static final Pattern BRANCH = Pattern.compile("\\d{4}");

    private final RestClient http;
    private final Map<String, PixProperties.Participant> participantsByIspb;
    private final Map<String, CachedToken> tokens = new ConcurrentHashMap<>();
    private final Clock clock;

    WalletCoreClient(RestClient.Builder builder, PixProperties props, Clock clock) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(props.walletCore().connectTimeout());
        requestFactory.setReadTimeout(props.walletCore().readTimeout());
        this.http = builder.baseUrl(props.walletCore().baseUrl().toString()).requestFactory(requestFactory).build();
        this.participantsByIspb = props.participants().stream()
                .collect(Collectors.toMap(PixProperties.Participant::ispb, Function.identity()));
        this.clock = clock;
    }

    record HolderCheckRequest(String branch, String number, String checkDigit, String taxId) {
    }

    record HolderCheckResponse(String result, UUID accountId) {
    }

    record CounterpartyRequest(String name, String taxId, String ispb, String branch, String account) {
    }

    record PixTransactionRequest(String type, BigDecimal amount, String description, String endToEndId,
                                 String returnId, UUID relatedTransactionId, CounterpartyRequest counterparty,
                                 String reasonCode, String remittanceInfo) {
    }

    record TransactionResponse(UUID id, boolean replayed, String endToEndId) {
    }

    record TokenResponse(String access_token, long expires_in) {
    }

    record AccountResponse(UUID id, String branch, String number, String checkDigit, String status,
                           String customerName) {
    }

    record ReversalRequest(String description, String reasonCode) {
    }

    record Problem(String code, String detail) {
    }

    private record CachedToken(String value, Instant expiresAt) {
    }

    @Override
    public HolderCheckResult checkHolder(String ispb, String branch, String number, String checkDigit, String taxId) {
        HolderCheckResponse response = call(ispb, null, "holder check", token -> http.post()
                .uri("/v1/accounts/holder-check")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .body(new HolderCheckRequest(branch, number, checkDigit, taxId))
                .retrieve()
                .body(HolderCheckResponse.class));
        return new HolderCheckResult(HolderCheckResult.Outcome.valueOf(response.result()), response.accountId());
    }

    @Override
    public Optional<PayerAccount> findAccount(String ispb, UUID accountId) {
        try {
            AccountResponse a = call(ispb, null, "account lookup", token -> http.get()
                    .uri("/v1/accounts/{id}", accountId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .body(AccountResponse.class));
            return Optional.of(new PayerAccount(a.id(), a.branch(), a.number() + a.checkDigit(), a.customerName()));
        } catch (NotFound e) {
            return Optional.empty();
        }
    }

    @Override
    public DebitResult debit(String ispb, UUID accountId, long amountCents, String description,
                             String idempotencyKey, PixRecord pix) {
        try {
            TransactionResponse response = call(ispb, SEND_SCOPE, "debit", token -> http.post()
                    .uri("/v1/accounts/{id}/pix-debits", accountId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header("Idempotency-Key", idempotencyKey)
                    .body(request("PIX_OUT", amountCents, description, pix))
                    .retrieve()
                    .body(TransactionResponse.class));
            return new DebitResult.Debited(response.id(), response.endToEndId());
        } catch (Refused e) {
            // 422 (INSUFFICIENT_FUNDS, ACCOUNT_NOT_ACTIVE), 409 (IDEMPOTENCY_KEY_REUSED) or 400 (invalid Pix
            // data): an answer for the caller of the REST API; nothing was debited.
            return new DebitResult.Refused(e.code, e.detail);
        }
    }

    @Override
    public UUID reverse(String ispb, UUID debitTransactionId, String description, String reasonCode) {
        TransactionResponse response = call(ispb, SEND_SCOPE, "reversal", token -> http.post()
                .uri("/v1/transactions/{id}/reversals", debitTransactionId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .body(new ReversalRequest(description, reasonCode))
                .retrieve()
                .body(TransactionResponse.class));
        return response.id();
    }

    @Override
    public UUID credit(String ispb, UUID accountId, long amountCents, String description, String idempotencyKey,
                       PixRecord pix) {
        String type = pix.returnId() == null ? "PIX_IN" : "PIX_RETURN_IN";
        TransactionResponse response = call(ispb, RECEIVE_SCOPE, "credit", token -> http.post()
                .uri("/v1/accounts/{id}/pix-credits", accountId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Idempotency-Key", idempotencyKey)
                .body(request(type, amountCents, description, pix))
                .retrieve()
                .body(TransactionResponse.class));
        if (response.replayed()) {
            log.info("credit {} was already made (idempotent replay), transaction {}", idempotencyKey, response.id());
        }
        return response.id();
    }

    private static PixTransactionRequest request(String type, long amountCents, String description, PixRecord pix) {
        Counterparty c = pix.counterparty();
        // wallet-core keeps the branch only in its 4-digit form and the account as digits; what
        // the SPI sent in another shape is still in our own database, in full.
        String branch = c.branch() != null && BRANCH.matcher(c.branch()).matches() ? c.branch() : null;
        String account = c.accountNumber() == null ? null : c.accountNumber().replaceAll("\\D", "");
        String remittance = pix.remittanceInfo() == null || pix.remittanceInfo().length() <= 140
                ? pix.remittanceInfo() : pix.remittanceInfo().substring(0, 140);
        return new PixTransactionRequest(type, Amounts.toDecimal(amountCents), description, pix.endToEndId(),
                pix.returnId(), pix.relatedTransactionId(),
                new CounterpartyRequest(c.name(), c.taxId(), c.ispb(), branch, account), pix.reasonCode(), remittance);
    }

    /**
     * 404 from wallet-core. An answer where the caller expects it (account lookup); anywhere else
     * it stays a {@link PermanentFailure} - retrying a missing resource cannot help.
     */
    private static final class NotFound extends PermanentFailure {
        NotFound(String operation) {
            super("wallet-core: not found on " + operation);
        }
    }

    /**
     * 400/409/422 from wallet-core: a refusal (invalid data or a business rule) with its stable code. The debit turns it into
     * an answer for the REST caller; a refused credit or reversal (e.g. account blocked meanwhile)
     * stays a {@link PermanentFailure}, i.e. the message goes to the DLQ for someone to look at.
     */
    private static final class Refused extends PermanentFailure {
        final String code;
        final String detail;

        Refused(String operation, String code, String detail) {
            super("wallet-core refused " + operation + ": " + code + " " + detail);
            this.code = code;
            this.detail = detail;
        }
    }

    private <T> T call(String ispb, String scope, String operation, Function<String, T> request) {
        String cacheKey = ispb + "|" + (scope == null ? "all" : scope);
        try {
            try {
                return request.apply(token(ispb, scope, cacheKey));
            } catch (HttpClientErrorException.Unauthorized e) {
                tokens.remove(cacheKey); // expired or rotated key: get a fresh token, once
                return request.apply(token(ispb, scope, cacheKey));
            }
        } catch (ResourceAccessException | HttpServerErrorException | HttpClientErrorException.TooManyRequests e) {
            throw new RetryLater("wallet-core " + operation + " failed: " + e.getMessage(), e);
        } catch (HttpClientErrorException.NotFound e) {
            throw new NotFound(operation);
        } catch (HttpClientErrorException.BadRequest | HttpClientErrorException.Conflict
                 | HttpClientErrorException.UnprocessableContent e) {
            Problem p = e.getResponseBodyAs(Problem.class);
            throw new Refused(operation, p == null || p.code() == null ? e.getStatusCode().toString() : p.code(),
                    p == null ? e.getMessage() : p.detail());
        } catch (HttpClientErrorException e) {
            throw new PermanentFailure("wallet-core refused " + operation + " (" + e.getStatusCode() + "): "
                    + e.getResponseBodyAsString());
        }
    }

    private String token(String ispb, String scope, String cacheKey) {
        CachedToken cached = tokens.get(cacheKey);
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.value();
        }
        PixProperties.Participant p = participantsByIspb.get(ispb);
        if (p == null) {
            throw new PermanentFailure("no wallet-core credentials configured for ISPB " + ispb);
        }
        // URI templates (not a builder lambda) so the client metric gets a real uri tag, not "none".
        var spec = scope == null ? http.post().uri("/v1/auth/token")
                : http.post().uri("/v1/auth/token?scope={scope}", scope);
        TokenResponse response = spec
                .headers(h -> h.setBasicAuth(p.clientId(), p.clientSecret()))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                    throw new PermanentFailure("wallet-core rejected the credentials of ISPB " + ispb + " ("
                            + res.getStatusCode() + ")");
                })
                .body(TokenResponse.class);
        tokens.put(cacheKey, new CachedToken(response.access_token(),
                now.plusSeconds(Math.max(0, response.expires_in() - EXPIRY_MARGIN_SECONDS))));
        return response.access_token();
    }
}
