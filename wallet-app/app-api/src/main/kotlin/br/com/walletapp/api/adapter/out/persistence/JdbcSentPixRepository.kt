package br.com.walletapp.api.adapter.out.persistence

import br.com.walletapp.api.application.port.SentPix
import br.com.walletapp.api.application.port.SentPixRepository
import br.com.walletapp.api.domain.LoginId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class JdbcSentPixRepository(private val jdbc: JdbcClient) : SentPixRepository {

    /** A retried send gets the same EndToEndId back from wallet-pix: recording it again changes nothing. */
    override fun record(pix: SentPix) {
        jdbc.sql(
            """
            INSERT INTO sent_pix (end_to_end_id, login_id, payee_name, payee_ispb, amount_cents)
            VALUES (:e2e, :login, :name, :ispb, :amount)
            ON CONFLICT (end_to_end_id) DO NOTHING
            """.trimIndent(),
        )
            .param("e2e", pix.endToEndId)
            .param("login", pix.loginId.value)
            .param("name", pix.payeeName)
            .param("ispb", pix.payeeIspb)
            .param("amount", pix.amountCents)
            .update()
    }

    override fun find(loginId: LoginId, endToEndId: String): SentPix? =
        jdbc.sql("SELECT * FROM sent_pix WHERE end_to_end_id = :e2e AND login_id = :login")
            .param("e2e", endToEndId)
            .param("login", loginId.value)
            .query { rs, _ ->
                SentPix(LoginId(rs.getObject("login_id", UUID::class.java)), rs.getString("end_to_end_id"),
                    rs.getString("payee_name"), rs.getString("payee_ispb"), rs.getLong("amount_cents"))
            }
            .optional().orElse(null)
}
