package br.com.walletapp.desktop.ui

import br.com.walletapp.contract.StatementEntry
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How the app writes money, dates and transaction types - in pt-BR, from the contract's raw values. */
object Format {

    private val taxIdShape = Regex("""[0-9]{11}|[0-9A-Z]{12}[0-9]{2}""")

    /** Shown in the form when [isTaxId] says no, before anything is sent. */
    const val TAX_ID_ERROR = "Informe o CPF (11 dígitos) ou o CNPJ (14 caracteres) do recebedor."

    /** A CPF (11 digits) or CNPJ (14 characters), formatting ignored. Only the shape: app-api checks the rest. */
    fun isTaxId(raw: String): Boolean = taxIdShape.matches(raw.filter(Char::isLetterOrDigit).uppercase())

    private val brl = NumberFormat.getCurrencyInstance(Locale.of("pt", "BR"))
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val dateTime = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(zone)
    private val shortDate = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(zone)

    /** Cents to "R$ 1.234,56" - from a Long, never through a Double. */
    fun money(cents: Long): String = brl.format(BigDecimal.valueOf(cents, 2)).replace(' ', ' ')

    /** "+ R$ 10,00" / "- R$ 10,00", as a statement line shows it. */
    fun signed(entry: StatementEntry): String = (if (entry.credit) "+ " else "- ") + money(entry.amountCents)

    fun dateTime(iso: String): String = dateTime.format(Instant.parse(iso))

    fun shortDate(iso: String): String = shortDate.format(Instant.parse(iso))

    /** Same names as the console's statement. */
    fun type(type: String): String = when (type) {
        "DEPOSIT" -> "Depósito"
        "WITHDRAWAL" -> "Saque"
        "TRANSFER" -> "Transferência"
        "PIX_IN" -> "Pix recebido"
        "PIX_OUT" -> "Pix enviado"
        "PIX_REFUND" -> "Estorno de Pix"
        "PIX_RETURN_IN" -> "Devolução recebida"
        "PIX_RETURN_OUT" -> "Devolução enviada"
        else -> type
    }

    /**
     * What the customer typed as an amount, in cents: "1.234,56", "10,5" or "10". Null when it is not a
     * positive amount with at most two decimals. BigDecimal all the way - never a Double.
     */
    fun parseCents(text: String): Long? {
        val t = text.trim().removePrefix("R$").trim()
        if (!Regex("""\d{1,3}(\.\d{3})*(,\d{1,2})?|\d+(,\d{1,2})?""").matches(t)) return null
        val cents = BigDecimal(t.replace(".", "").replace(',', '.')).movePointRight(2).longValueExact()
        return cents.takeIf { it > 0 }
    }

    private val dayMonthYear = DateTimeFormatter.ofPattern("dd/MM/uuuu")
    private val hourMinute = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)

    /** "08/10/2026" typed by the customer to "2026-10-08"; null when it is not a real date. */
    fun parseDate(text: String): String? =
        runCatching { LocalDate.parse(text.trim(), dayMonthYear.withResolverStyle(java.time.format.ResolverStyle.STRICT)) }
            .getOrNull()?.toString()

    /** "2026-10-08" to "08/10/2026". */
    fun date(iso: String): String = LocalDate.parse(iso).format(dayMonthYear)

    /** The schedule's situation in one phrase - the same words the console uses. */
    fun scheduleStatus(s: br.com.walletapp.contract.Schedule): String {
        if (s.status == "CANCELLED") return "Cancelado"
        val e = s.execution
        return when (e.status) {
            "EXECUTED" -> "Pago"
            "FAILED" -> "Não pago"
            "PROCESSING" -> "Em processamento"
            "CANCELLED" -> "Cancelado"
            else -> if (e.attemptCount > 0 && e.nextAttemptAt != null)
                "Nova tentativa às ${hourMinute.format(Instant.parse(e.nextAttemptAt))}" else "Agendado"
        }
    }

    /** "0001 / 00100123-8". */
    fun account(branch: String, number: String, checkDigit: String) = "$branch / $number-$checkDigit"
}
