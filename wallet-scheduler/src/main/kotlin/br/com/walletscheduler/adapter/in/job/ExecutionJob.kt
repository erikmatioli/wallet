package br.com.walletscheduler.adapter.`in`.job

import br.com.walletscheduler.application.ExecutionService
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Wakes up every poll interval and runs whatever is due. Several instances can run at once: each
 * claim takes different rows (SKIP LOCKED), so no execution is taken twice.
 */
@Component
class ExecutionJob(private val executions: ExecutionService) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${scheduler.job.poll-interval}")
    fun run() {
        try {
            do {
                val claimed = executions.claim()
                claimed.forEach { work ->
                    // Outside any request: the tenant only reaches the logs because it is put here.
                    MDC.putCloseable("tenant_id", work.tenantId.toString()).use {
                        val outcome = executions.execute(work)
                        log.info("schedule {} attempt {}: {}", work.schedule.id, work.attempt.number, outcome)
                    }
                }
            } while (claimed.isNotEmpty())
        } catch (e: RuntimeException) {
            // The next tick tries again; whatever was claimed comes back when its lease expires.
            log.warn("execution job failed, will retry on next tick: {}", e.toString())
        }
    }
}
