package br.com.walletcore.domain.pix;

import br.com.walletcore.domain.exception.ValidationException;
import br.com.walletcore.domain.ledger.TransactionType;
import br.com.walletcore.domain.shared.TransactionId;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What a Pix transaction carries besides the money movement (ADR-010): which Pix it is, who the
 * counterparty was, and - for a refund or a return - which transaction it refers to. Stored once,
 * next to the transaction, so a statement is complete without asking the Pix service.
 *
 * @param endToEndId           EndToEndId of the Pix (a refund or return carries the original's)
 * @param returnId             id of the return (RtrId), only for PIX_RETURN_IN / PIX_RETURN_OUT
 * @param relatedTransactionId the original Pix transaction, for PIX_REFUND and PIX_RETURN_*
 * @param reasonCode           SPI reason of a refund or return (e.g. AC03, MD06)
 * @param remittanceInfo       free text the payer wrote
 */
public record PixDetail(TransactionType type, String endToEndId, String returnId,
                        TransactionId relatedTransactionId, PixCounterparty counterparty, String reasonCode,
                        String remittanceInfo) {

    // E + ISPB (8) + yyyyMMddHHmm (12) + 11 alphanumerics; a return id has the same shape with D.
    private static final Pattern END_TO_END = Pattern.compile("E\\d{20}[A-Za-z0-9]{11}");
    private static final Pattern RETURN_ID = Pattern.compile("D\\d{20}[A-Za-z0-9]{11}");
    private static final Pattern REASON = Pattern.compile("[A-Z0-9]{2,8}");
    private static final int MAX_REMITTANCE = 140;

    public PixDetail {
        Objects.requireNonNull(type, "type");
        if (!type.isPix()) {
            throw new IllegalArgumentException(type + " is not a Pix transaction type");
        }
        if (endToEndId == null || !END_TO_END.matcher(endToEndId).matches()) {
            throw invalid("endToEndId is not a valid EndToEndId");
        }
        if (counterparty == null) {
            throw invalid("counterparty is required");
        }
        boolean isReturn = type == TransactionType.PIX_RETURN_IN || type == TransactionType.PIX_RETURN_OUT;
        if (isReturn != (returnId != null)) {
            throw invalid(isReturn ? "returnId is required for a return" : "returnId is only allowed for a return");
        }
        if (returnId != null && !RETURN_ID.matcher(returnId).matches()) {
            throw invalid("returnId is not a valid return id");
        }
        boolean refersToOriginal = isReturn || type == TransactionType.PIX_REFUND;
        if (refersToOriginal != (relatedTransactionId != null)) {
            throw invalid(refersToOriginal ? "relatedTransactionId is required for a refund or return"
                    : "relatedTransactionId is only allowed for a refund or return");
        }
        if (reasonCode != null && !REASON.matcher(reasonCode).matches()) {
            throw invalid("reasonCode must have 2 to 8 uppercase letters or digits");
        }
        if (isReturn && reasonCode == null) {
            throw invalid("reasonCode is required for a return");
        }
        remittanceInfo = remittanceInfo == null || remittanceInfo.isBlank() ? null : remittanceInfo.strip();
        if (remittanceInfo != null && remittanceInfo.length() > MAX_REMITTANCE) {
            throw invalid("remittanceInfo must have at most 140 characters");
        }
    }

    /** The refund of a PIX_OUT: same Pix and counterparty, pointing back at the original. */
    public PixDetail refundOf(TransactionId original, String refundReasonCode) {
        return new PixDetail(TransactionType.PIX_REFUND, endToEndId, null, original, counterparty, refundReasonCode,
                null);
    }

    private static ValidationException invalid(String message) {
        return new ValidationException("INVALID_PIX_DETAIL", message);
    }
}
