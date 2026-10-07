package br.com.walletscheduler.config

import br.com.walletscheduler.application.ExecutionService
import br.com.walletscheduler.application.ScheduleService
import br.com.walletscheduler.application.port.PixPayments
import br.com.walletscheduler.application.port.ScheduleRepository
import br.com.walletscheduler.application.port.Transactions
import br.com.walletscheduler.application.port.WalletCore
import br.com.walletscheduler.domain.ExecutionWindows
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock
import java.time.ZoneId

/**
 * Wires the framework-free use cases to their ports - the only place where they meet Spring, as
 * wallet-core's UseCaseConfig.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(SchedulerProperties::class)
class SchedulerConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun executionWindows(props: SchedulerProperties) =
        ExecutionWindows(ZoneId.of(props.windows.zone), props.windows.times)

    @Bean
    fun scheduleService(walletCore: WalletCore, repository: ScheduleRepository, tx: Transactions,
                        windows: ExecutionWindows, clock: Clock) =
        ScheduleService(walletCore, repository, tx, windows, clock)

    @Bean
    fun executionService(walletCore: WalletCore, pix: PixPayments, repository: ScheduleRepository, tx: Transactions,
                         windows: ExecutionWindows, clock: Clock, props: SchedulerProperties) =
        ExecutionService(walletCore, pix, repository, tx, windows, clock,
            ExecutionService.Settings(props.job.batchSize, props.job.lease, props.job.unknownRetryIn,
                props.job.pixResultWait))
}
