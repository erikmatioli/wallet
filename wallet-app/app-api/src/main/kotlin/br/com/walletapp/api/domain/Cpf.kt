package br.com.walletapp.api.domain

/** A valid CPF, digits only. Formatting (dots, dash) is accepted on input and dropped. */
@JvmInline
value class Cpf private constructor(val digits: String) {

    /** Safe to show and to log: only the last four digits, like wallet-core's masking. */
    val masked: String get() = "***" + digits.takeLast(4)

    override fun toString() = masked // never the full CPF by accident in a log line

    companion object {
        fun parse(raw: String): Cpf {
            val digits = raw.filter(Char::isDigit)
            if (raw.any { !it.isDigit() && it !in ".- " } || !valid(digits)) {
                throw ValidationException("INVALID_CPF", "CPF inválido.")
            }
            return Cpf(digits)
        }

        private fun valid(d: String): Boolean {
            if (d.length != 11 || d.toSet().size == 1) return false
            fun digit(length: Int): Int {
                val sum = (0 until length).sumOf { (d[it] - '0') * (length + 1 - it) }
                return (sum * 10 % 11).let { if (it == 10) 0 else it }
            }
            return digit(9) == d[9] - '0' && digit(10) == d[10] - '0'
        }
    }
}
