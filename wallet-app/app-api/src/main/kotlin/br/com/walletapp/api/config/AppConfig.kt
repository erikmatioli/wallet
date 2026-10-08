package br.com.walletapp.api.config

import br.com.walletapp.api.application.AccountService
import br.com.walletapp.api.application.AuthService
import br.com.walletapp.api.application.PaymentService
import br.com.walletapp.api.application.ScheduleService
import br.com.walletapp.api.application.port.SchedulerGateway
import br.com.walletapp.api.application.port.CoreBanking
import br.com.walletapp.api.application.port.LoginRepository
import br.com.walletapp.api.application.port.PasswordHasher
import br.com.walletapp.api.application.port.PixGateway
import br.com.walletapp.api.application.port.SentPixRepository
import br.com.walletapp.api.application.port.SessionTokens
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** Wires the framework-free use cases to their ports - the only place where they meet Spring. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AppProperties::class)
class AppConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun authService(core: CoreBanking, logins: LoginRepository, hasher: PasswordHasher, tokens: SessionTokens,
                    clock: Clock) = AuthService(core, logins, hasher, tokens, clock)

    @Bean
    fun accountService(core: CoreBanking) = AccountService(core)

    @Bean
    fun paymentService(core: CoreBanking, pix: PixGateway, logins: LoginRepository, sentPix: SentPixRepository) =
        PaymentService(core, pix, logins, sentPix)

    @Bean
    fun scheduleService(scheduler: SchedulerGateway, logins: LoginRepository, clock: Clock) =
        ScheduleService(scheduler, logins, clock)
}
