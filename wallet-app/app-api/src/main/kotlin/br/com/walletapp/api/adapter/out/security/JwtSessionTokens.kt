package br.com.walletapp.api.adapter.out.security

import br.com.walletapp.api.application.port.IssuedToken
import br.com.walletapp.api.application.port.SessionTokens
import br.com.walletapp.api.config.AppProperties
import br.com.walletapp.api.config.SessionClaims
import br.com.walletapp.api.domain.CustomerLogin
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * The customer's token: a JWT signed by app-api with its own key (HS256), never wallet-core's. `sub` is
 * the login, `account_id` the only account it opens.
 */
@Component
class JwtSessionTokens(private val encoder: JwtEncoder, private val props: AppProperties, private val clock: Clock) :
    SessionTokens {

    override fun issue(login: CustomerLogin): IssuedToken {
        val now = clock.instant()
        val ttl = props.session.ttl
        val claims = JwtClaimsSet.builder()
            .issuer(SessionClaims.issuer(props.walletCore.clientId))
            .subject(login.id.toString())
            .issuedAt(now)
            .expiresAt(now.plus(ttl))
            .claim(SessionClaims.ACCOUNT_ID, login.accountId!!.toString())
            .claim(SessionClaims.TENANT, props.walletCore.clientId)
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        return IssuedToken(encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue, ttl.toSeconds())
    }
}
