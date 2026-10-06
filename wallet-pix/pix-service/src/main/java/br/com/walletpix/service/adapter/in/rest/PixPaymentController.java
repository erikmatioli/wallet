package br.com.walletpix.service.adapter.in.rest;

import br.com.walletpix.service.application.MessageFailures.PermanentFailure;
import br.com.walletpix.service.application.MessageFailures.RetryLater;
import br.com.walletpix.service.application.SendPixService;
import br.com.walletpix.service.application.SendPixService.InitiateCommand;
import br.com.walletpix.service.application.SendPixService.InitiateResult;
import br.com.walletpix.service.application.SendPixService.PaymentRefused;
import br.com.walletpix.service.application.port.PixPorts.PixPaymentRepository;
import br.com.walletpix.service.config.PixProperties;
import br.com.walletpix.service.domain.Amounts;
import br.com.walletpix.service.domain.PixPayment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Starts an outgoing Pix. Synchronous up to the debit: 202 means the payer was debited and the
 * pacs.008 is on its way; the outcome (completed, or refunded) arrives later as a PixEvent and
 * can be polled with GET. A refusal (policy, payer check, insufficient funds) is answered here
 * and nothing is debited or sent.
 *
 * <p>The tenant - and therefore the payer's ISPB - comes from the token (its {@code sub}, the
 * wallet-core client id), never from the body.
 */
@RestController
@RequestMapping("/v1/pix/payments")
class PixPaymentController {

    private static final Logger log = LoggerFactory.getLogger(PixPaymentController.class);

    record PixPaymentRequest(
            @NotNull UUID payerAccountId,
            @NotBlank @Size(max = 32) String payerTaxId,
            @NotNull @Valid Payee payee,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @Size(max = 140) String description) {
    }

    /** {@code accountNumber}: number with the check digit appended, as the SPI carries it. */
    record Payee(
            @NotBlank @Pattern(regexp = "\\d{8}") String ispb,
            @NotBlank @Pattern(regexp = "\\d{4}") String branch,
            @NotBlank @Pattern(regexp = "\\d{2,21}") String accountNumber,
            @NotBlank @Size(max = 32) String taxId,
            @NotBlank @Size(max = 140) String name) {
    }

    record PixPaymentResponse(String endToEndId, String status, BigDecimal amount, UUID debitTransactionId,
                              String reasonCode, String payeeIspb) {

        static PixPaymentResponse from(PixPayment p) {
            return new PixPaymentResponse(p.endToEndId(), p.status().name(), Amounts.toDecimal(p.amountCents()),
                    p.debitTransactionId(), p.reasonCode(), p.counterpartIspb());
        }
    }

    private final SendPixService send;
    private final PixPaymentRepository payments;
    private final Map<String, String> ispbByClientId;

    PixPaymentController(SendPixService send, PixPaymentRepository payments, PixProperties props) {
        this.send = send;
        this.payments = payments;
        this.ispbByClientId = props.participants().stream()
                .collect(Collectors.toMap(PixProperties.Participant::clientId, PixProperties.Participant::ispb));
    }

    @PostMapping
    ResponseEntity<PixPaymentResponse> create(@AuthenticationPrincipal Jwt jwt,
                                              @RequestHeader("Idempotency-Key") String idempotencyKey,
                                              @Valid @RequestBody PixPaymentRequest r) {
        InitiateResult result = send.initiate(new InitiateCommand(ispbOf(jwt), idempotencyKey.strip(),
                r.payerAccountId(), r.payerTaxId(), r.payee().ispb(), r.payee().branch(), r.payee().accountNumber(),
                r.payee().taxId(), r.payee().name(), r.amount(), r.description()));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(PixPaymentResponse.from(result.payment()));
    }

    /** Status of one of the caller's outgoing Pix. Another tenant's EndToEndId is simply not found. */
    @GetMapping("/{endToEndId}")
    ResponseEntity<PixPaymentResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable String endToEndId) {
        String ispb = ispbOf(jwt);
        return payments.find(endToEndId, PixPayment.Direction.OUTBOUND)
                .filter(p -> p.ispb().equals(ispb))
                .map(p -> ResponseEntity.ok(PixPaymentResponse.from(p)))
                .orElse(ResponseEntity.notFound().build());
    }

    private String ispbOf(Jwt jwt) {
        String ispb = ispbByClientId.get(jwt.getSubject());
        if (ispb == null || jwt.getClaimAsString("tenant_id") == null) {
            throw new PaymentRefused("NOT_A_PARTICIPANT", "this tenant is not served by the Pix service");
        }
        return ispb;
    }

    // ------------------------------------------------------------------ errors (RFC 9457, same shape as wallet-core)

    private static final Map<String, HttpStatus> STATUS_BY_CODE = Map.of(
            "IDEMPOTENCY_KEY_REUSED", HttpStatus.CONFLICT,
            "IDEMPOTENCY_KEY_REQUIRED", HttpStatus.BAD_REQUEST,
            "INVALID_REQUEST", HttpStatus.BAD_REQUEST,
            "INVALID_PAYEE", HttpStatus.BAD_REQUEST,
            "INVALID_AMOUNT", HttpStatus.BAD_REQUEST,
            "NOT_A_PARTICIPANT", HttpStatus.FORBIDDEN);

    @ExceptionHandler(PaymentRefused.class)
    ResponseEntity<ProblemDetail> refused(PaymentRefused e) {
        return problem(STATUS_BY_CODE.getOrDefault(e.code(), HttpStatus.UNPROCESSABLE_CONTENT), e.code(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalidBody(MethodArgumentNotValidException e) {
        String fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage()).sorted().collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", fields);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ProblemDetail> missingHeader(MissingRequestHeaderException e) {
        return problem(HttpStatus.BAD_REQUEST, "Idempotency-Key".equalsIgnoreCase(e.getHeaderName())
                ? "IDEMPOTENCY_KEY_REQUIRED" : "MISSING_HEADER", e.getHeaderName() + " header is required");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "request could not be parsed");
    }

    @ExceptionHandler(RetryLater.class)
    ResponseEntity<ProblemDetail> unavailable(RetryLater e) {
        // Safe to retry with the same Idempotency-Key: a debit that did happen is replayed, not repeated.
        log.warn("send API dependency unavailable: {}", e.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "TEMPORARILY_UNAVAILABLE", "try again with the same Idempotency-Key");
    }

    @ExceptionHandler(PermanentFailure.class)
    ResponseEntity<ProblemDetail> upstream(PermanentFailure e) {
        log.error("send API failed upstream: {}", e.getMessage());
        return problem(HttpStatus.BAD_GATEWAY, "UPSTREAM_FAILURE", "the payment could not be processed");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("code", code);
        return ResponseEntity.status(status).body(pd);
    }
}
