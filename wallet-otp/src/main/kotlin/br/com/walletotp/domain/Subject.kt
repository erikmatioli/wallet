package br.com.walletotp.domain

/**
 * Who is being verified: a CPF (11 digits) or CNPJ (14, letters allowed in the first 12 - the
 * alphanumeric CNPJ). Formatting is dropped. The engine does not check the check digits: it does not
 * decide whether the subject exists, the caller does (ADR-001, decision 2).
 */
@JvmInline
value class Subject private constructor(val value: String) {

    /** Safe to log: only the last four characters. */
    override fun toString() = "***" + value.takeLast(4)

    companion object {
        private val FORMAT = Regex("""\d{11}|[0-9A-Z]{12}\d{2}""")

        fun of(raw: String?): Subject {
            val v = raw.orEmpty().filter(Char::isLetterOrDigit).uppercase()
            if (!FORMAT.matches(v)) throw ValidationException("INVALID_SUBJECT", "subject must be a CPF (11 digits) or CNPJ (14)")
            return Subject(v)
        }
    }
}

/** The email the code is sent to. Only used to send; the database keeps [masked] (ADR-001, decision 4). */
@JvmInline
value class Email private constructor(val value: String) {

    /** "e***@gmail.com": enough for the customer to recognize it, not enough to write to it. */
    val masked: String get() = value.first() + "***" + value.substring(value.indexOf('@'))

    override fun toString() = masked

    companion object {
        private val FORMAT = Regex("""[^@\s]+@[^@\s]+\.[^@\s]+""")

        fun of(raw: String?): Email {
            val v = raw.orEmpty().trim().lowercase()
            if (v.length > 254 || !FORMAT.matches(v)) throw ValidationException("INVALID_DESTINATION", "destination must be an email address")
            return Email(v)
        }
    }
}
