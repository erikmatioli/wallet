package br.com.walletotp.adapter.out.mail

import br.com.walletotp.application.port.CodeSender
import br.com.walletotp.config.OtpProperties
import br.com.walletotp.domain.Email
import br.com.walletotp.domain.Purpose
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The code by email, as plain text (ADR-001, decision 6). Locally the SMTP server is Mailpit. The text
 * says what the code is for, so a code the customer did not ask for is noticed.
 */
@Component
class SmtpCodeSender(private val mail: JavaMailSender, props: OtpProperties) : CodeSender {

    private val from = props.mail.from

    override fun send(destination: Email, code: String, purpose: Purpose, expiresAt: Instant) {
        val message = SimpleMailMessage().apply {
            setFrom(from)
            setTo(destination.value)
            subject = "Seu código Wallet: $code"
            text = """
                |${what(purpose)}
                |
                |    $code
                |
                |O código vale até as ${TIME.format(expiresAt)} (horário de Brasília) e só pode ser usado uma vez.
                |
                |Se não foi você quem pediu, ignore este e-mail. Ninguém da Wallet pede este código por telefone ou mensagem.
                """.trimMargin()
        }
        mail.send(message)
    }

    private fun what(purpose: Purpose) = when (purpose) {
        Purpose.SIGNUP -> "Use este código para confirmar seu e-mail e abrir sua conta:"
        Purpose.LOGIN -> "Use este código para entrar na sua conta:"
        Purpose.PAYMENT_APPROVAL -> "Use este código para aprovar o pagamento:"
    }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("America/Sao_Paulo"))
    }
}
