package br.com.walletotp.config

import br.com.walletotp.application.ChallengeService
import br.com.walletotp.application.RandomCodes
import br.com.walletotp.application.Secrets
import br.com.walletotp.application.port.ChallengeRepository
import br.com.walletotp.application.port.CodeGenerator
import br.com.walletotp.application.port.CodeSender
import br.com.walletotp.application.port.OtpMetrics
import br.com.walletotp.application.port.Transactions
import br.com.walletotp.domain.SendLimits
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * Wires the framework-free use cases to their ports - the only place where they meet Spring, as
 * wallet-scheduler's SchedulerConfig.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OtpProperties::class)
class OtpConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun codeGenerator(): CodeGenerator = RandomCodes()

    @Bean
    fun secrets(props: OtpProperties) = Secrets(props.codeKey.toByteArray())

    @Bean
    fun sendLimits() = SendLimits()

    @Bean
    fun challengeService(challenges: ChallengeRepository, sender: CodeSender, codes: CodeGenerator, secrets: Secrets,
                         limits: SendLimits, tx: Transactions, metrics: OtpMetrics, clock: Clock) =
        ChallengeService(challenges, sender, codes, secrets, limits, tx, metrics, clock)
}
