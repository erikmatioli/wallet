package br.com.walletcore.domain.backing;

/**
 * Where the tenant keeps the money that backs its customers' balances (ADR-011). Each payment rail moves
 * money in one of these places; the treasury moves it between them.
 */
public enum BackingType {
    /** Conta PI at the central bank: Pix settles here (SPI). */
    PIX_PI("Conta PI"),
    /** Reservas Bancárias or Conta de Liquidação: TED (STR), and where the Conta PI is topped up from. */
    RESERVES("Reservas"),
    /** CCME or federal bonds: the regulatory backing of e-money. */
    CCME("CCME"),
    /** The tenant's own investment accounts, from which the treasury covers what was credited by hand. */
    INVESTMENT("Investimento"),
    /** Cash and ATMs: a withdrawal hands out bank notes. */
    CASH("Caixa"),
    /** The credit portfolio: loans granted and paid back. */
    CREDIT_PORTFOLIO("Carteira de crédito"),
    /** "A regularizar": money created by a deposit, not yet covered by the treasury. */
    UNASSIGNED("A regularizar");

    private final String defaultName;

    BackingType(String defaultName) {
        this.defaultName = defaultName;
    }

    public String defaultName() {
        return defaultName;
    }

    /** Every tenant has exactly one of these, created with the tenant: the rails route to them. */
    public boolean isDefault() {
        return this == PIX_PI || this == CASH || this == UNASSIGNED;
    }
}
