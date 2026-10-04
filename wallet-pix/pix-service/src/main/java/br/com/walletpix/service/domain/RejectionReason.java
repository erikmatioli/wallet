package br.com.walletpix.service.domain;

/**
 * Rejection reasons this service sends in a pacs.002 RJCT when it is the receiving PSP. Codes
 * and meanings follow the SPI message catalog; confirm them against the catalog version in
 * force before going to production, since the BCB revises it.
 */
public enum RejectionReason {
    /** Branch and/or account of the receiving user does not exist or is invalid. */
    AC03("conta do recebedor inexistente ou inválida"),
    /** The receiving user's account is blocked. */
    AC06("conta do recebedor bloqueada"),
    /** The receiving user's account is closed. */
    AC07("conta do recebedor encerrada"),
    /** Wrong account type for the given account. */
    AC14("tipo de conta incorreto"),
    /** CPF/CNPJ of the receiving user is not the holder of the account. */
    BE01("CPF/CNPJ não corresponde ao titular da conta");

    private final String description;

    RejectionReason(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
