package br.com.walletcore.domain.ledger;

/**
 * What a transaction is. DEPOSIT, WITHDRAWAL and TRANSFER are generic money movements; the PIX_*
 * types record what happened in the Pix arrangement (ADR-010), so a statement can tell a Pix from a
 * deposit and the core can enforce the rules that tie refunds and returns to the original Pix.
 */
public enum TransactionType {
    DEPOSIT,
    WITHDRAWAL,
    TRANSFER,
    /** Pix received (incoming pacs.008). Credits the customer. */
    PIX_IN,
    /** Pix sent. Debits the customer. */
    PIX_OUT,
    /** Refund of a PIX_OUT the SPI rejected: no Pix was settled. Credits back the full amount, once. */
    PIX_REFUND,
    /** Return received (incoming pacs.004) of a settled PIX_OUT. Credits the customer, in full or in part. */
    PIX_RETURN_IN,
    /** Return sent (outgoing pacs.004) of a settled PIX_IN. Debits the customer, in full or in part. */
    PIX_RETURN_OUT,
    /** The treasury moves money between two backing accounts (ADR-011): never touches a customer. */
    TREASURY_TRANSFER,
    /** One-off move of the settlement's balance to the backing accounts, when they were introduced (ADR-011). */
    RECLASSIFICATION,
    /** Money a court ordered out of a blocked account (ADR-013): to another customer or to the judicial transit. */
    JUDICIAL_TRANSFER,
    /** The treasury sent the TED of a judicial transfer: out of the judicial transit, from a backing account. */
    JUDICIAL_TRANSIT_OUT;

    public boolean isPix() {
        return name().startsWith("PIX_");
    }

    /** Whether the customer account is credited (true) or debited (false). Only defined for Pix types. */
    public boolean creditsCustomer() {
        return switch (this) {
            case PIX_IN, PIX_REFUND, PIX_RETURN_IN -> true;
            case PIX_OUT, PIX_RETURN_OUT -> false;
            case DEPOSIT, WITHDRAWAL, TRANSFER, TREASURY_TRANSFER, RECLASSIFICATION, JUDICIAL_TRANSFER,
                 JUDICIAL_TRANSIT_OUT ->
                    throw new IllegalStateException(this + " has no fixed customer direction");
        };
    }
}
