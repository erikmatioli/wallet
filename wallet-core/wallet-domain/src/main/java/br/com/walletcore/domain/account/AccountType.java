package br.com.walletcore.domain.account;

/**
 * Account types as used by the Brazilian payment ecosystem (SPI/PIX): CACC, SVGS, SLRY and
 * TRAN (conta de pagamento). This platform issues payment accounts only.
 */
public enum AccountType {
    PAYMENT("TRAN");

    private final String bcbCode;

    AccountType(String bcbCode) {
        this.bcbCode = bcbCode;
    }

    public String bcbCode() {
        return bcbCode;
    }
}
