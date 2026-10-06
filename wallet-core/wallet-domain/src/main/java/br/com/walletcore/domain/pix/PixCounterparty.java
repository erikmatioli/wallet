package br.com.walletcore.domain.pix;

import br.com.walletcore.domain.customer.TaxId;
import br.com.walletcore.domain.exception.ValidationException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The other side of a Pix, as it was when the transaction was posted: the payee of a PIX_OUT, the
 * payer of a PIX_IN. Only the masked CPF/CNPJ is kept - the full document stays in the Pix
 * service's own database, which needs it for reconciliation.
 *
 * @param branch      null when the counterparty's account has no branch
 * @param accountType the SPI account type (e.g. TRAN, CACC), null when unknown
 */
public record PixCounterparty(String name, String taxIdMasked, String ispb, String branch, String account,
                              String accountType) {

    private static final Pattern ISPB = Pattern.compile("\\d{8}");
    private static final Pattern BRANCH = Pattern.compile("\\d{4}");
    private static final Pattern ACCOUNT = Pattern.compile("\\d{1,21}");
    private static final Pattern ACCOUNT_TYPE = Pattern.compile("[A-Z]{4}");
    private static final Pattern TAX_ID_SHAPE = Pattern.compile("[0-9]{11}|[0-9A-Z]{12}[0-9]{2}");
    private static final int MAX_NAME = 140;

    public PixCounterparty {
        name = name == null ? "" : name.strip();
        if (name.isEmpty() || name.length() > MAX_NAME) {
            throw invalid("counterparty name is required and must have at most 140 characters");
        }
        if (taxIdMasked == null || taxIdMasked.isBlank()) {
            throw invalid("counterparty tax id is required");
        }
        if (ispb == null || !ISPB.matcher(ispb).matches()) {
            throw invalid("counterparty ISPB must have 8 digits");
        }
        if (branch != null && !BRANCH.matcher(branch).matches()) {
            throw invalid("counterparty branch must have 4 digits");
        }
        if (account == null || !ACCOUNT.matcher(account).matches()) {
            throw invalid("counterparty account must have 1 to 21 digits");
        }
        if (accountType != null && !ACCOUNT_TYPE.matcher(accountType).matches()) {
            throw invalid("counterparty account type must have 4 letters");
        }
    }

    /**
     * Builds the counterparty from the full CPF/CNPJ, masked here the same way as {@link TaxId#masked()}.
     * Only the shape is checked (11 or 14 characters), not the check digits: the document comes
     * from the SPI or from the payee data the Pix service already validated, and refusing to
     * credit a Pix that was already accepted over a document we merely display would be worse.
     */
    public static PixCounterparty of(String name, String rawTaxId, String ispb, String branch, String account,
                                     String accountType) {
        String doc = rawTaxId == null ? "" : rawTaxId.replaceAll("[.\\-/\\s]", "").toUpperCase(Locale.ROOT);
        if (!TAX_ID_SHAPE.matcher(doc).matches()) {
            throw invalid("counterparty tax id must be a CPF (11 digits) or CNPJ (14 characters)");
        }
        return new PixCounterparty(name, "***" + doc.substring(doc.length() - 4), ispb, branch, account, accountType);
    }

    private static ValidationException invalid(String message) {
        return new ValidationException("INVALID_PIX_COUNTERPARTY", message);
    }
}
