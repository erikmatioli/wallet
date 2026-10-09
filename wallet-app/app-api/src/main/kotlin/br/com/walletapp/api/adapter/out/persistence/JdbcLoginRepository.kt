package br.com.walletapp.api.adapter.out.persistence

import br.com.walletapp.api.application.port.LoginRepository
import br.com.walletapp.api.domain.AccountId
import br.com.walletapp.api.domain.Cpf
import br.com.walletapp.api.domain.CustomerLogin
import br.com.walletapp.api.domain.Email
import br.com.walletapp.api.domain.LoginId
import br.com.walletapp.api.domain.LoginStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JdbcLoginRepository(private val jdbc: JdbcClient) : LoginRepository {

    override fun find(cpf: Cpf): CustomerLogin? =
        jdbc.sql("SELECT * FROM customer_login WHERE cpf = :cpf")
            .param("cpf", cpf.digits)
            .query { rs, _ -> map(rs) }
            .optional().orElse(null)

    override fun findById(id: LoginId): CustomerLogin? =
        jdbc.sql("SELECT * FROM customer_login WHERE id = :id")
            .param("id", id.value)
            .query { rs, _ -> map(rs) }
            .optional().orElse(null)

    override fun insert(login: CustomerLogin): Boolean =
        jdbc.sql(
            """
            INSERT INTO customer_login (id, cpf, name, email, status, account_id, created_at, updated_at)
            VALUES (:id, :cpf, :name, :email, :status, :account, :createdAt, :createdAt)
            ON CONFLICT (cpf) DO NOTHING
            """.trimIndent(),
        ).bind(login).update() == 1

    override fun update(login: CustomerLogin) {
        jdbc.sql(
            """
            UPDATE customer_login
               SET name = :name, email = :email, status = :status, account_id = :account, updated_at = now()
             WHERE id = :id
            """.trimIndent(),
        ).bind(login).update()
    }

    override fun delete(id: LoginId) {
        jdbc.sql("DELETE FROM customer_login WHERE id = :id AND status = 'PENDING'").param("id", id.value).update()
    }

    private fun JdbcClient.StatementSpec.bind(l: CustomerLogin) = this
        .param("id", l.id.value)
        .param("cpf", l.cpf.digits)
        .param("name", l.name)
        .param("email", l.email?.value, Types.VARCHAR)
        .param("status", l.status.name)
        .param("account", l.accountId?.value, Types.OTHER)
        .param("createdAt", ts(l.createdAt))

    private companion object {
        fun ts(i: Instant): OffsetDateTime = i.atOffset(ZoneOffset.UTC)

        fun map(rs: ResultSet) = CustomerLogin(
            id = LoginId(rs.getObject("id", UUID::class.java)),
            cpf = Cpf.parse(rs.getString("cpf")),
            name = rs.getString("name"),
            email = rs.getString("email")?.let(Email::of),
            status = LoginStatus.valueOf(rs.getString("status")),
            accountId = rs.getObject("account_id", UUID::class.java)?.let(::AccountId),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
    }
}
