package br.com.walletscheduler.adapter.`in`.messaging

import br.com.walletscheduler.application.ExecutionService
import br.com.walletscheduler.application.port.NotReadyYetException
import br.com.walletscheduler.application.port.PixOutcome
import br.com.walletscheduler.config.SchedulerProperties
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.Message
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.time.Duration

/**
 * Long-polls this service's own queue on the `pix-payment-events` topic (ADR-001, decision 6) and closes
 * the executions of the Pix it sent. Same delivery rules as wallet-pix's consumer:
 * - handled (or not ours): deleted;
 * - early (the attempt has not recorded the send yet) or failed: left alone, back after the visibility
 *   timeout; after maxReceiveCount the queue's redrive policy moves it to the DLQ.
 */
@Component
@ConditionalOnProperty("scheduler.bus.enabled", havingValue = "true", matchIfMissing = true)
class PixEventsListener(
    private val sqs: SqsClient,
    private val executions: ExecutionService,
    private val json: JsonMapper,
    props: SchedulerProperties,
) : SmartLifecycle {

    private val log = LoggerFactory.getLogger(javaClass)
    private val queueName = props.bus.pixEventsQueue

    @Volatile
    private var running = false
    private var poller: Thread? = null

    /**
     * The fields this service reads from wallet-pix's PixEvent. A copy of the contract rather than a
     * dependency on pix-messages: the two projects build and release separately (ADR-009 of wallet-core).
     */
    data class PixEventMessage(val type: String, val endToEndId: String?, val requestId: String?, val reasonCode: String?)

    override fun start() {
        running = true
        poller = Thread.ofVirtual().name("sqs-$queueName").start(::loop)
    }

    override fun stop() {
        running = false
        poller?.interrupt()
    }

    override fun isRunning() = running

    private fun loop() {
        val url = queueUrl() ?: return
        while (running) {
            try {
                sqs.receiveMessage { it.queueUrl(url).maxNumberOfMessages(10).waitTimeSeconds(20) }
                    .messages().forEach { process(url, it) }
            } catch (e: Exception) {
                if (!running || e is InterruptedException) return
                log.warn("Polling {} failed, retrying: {}", queueName, e.toString())
                sleep(Duration.ofSeconds(2))
            }
        }
    }

    private fun process(url: String, message: Message) {
        try {
            val event = json.readValue<PixEventMessage>(message.body())
            handle(event)
            sqs.deleteMessage { it.queueUrl(url).receiptHandle(message.receiptHandle()) }
        } catch (e: NotReadyYetException) {
            log.info("Pix event early, will be handled on redelivery: {}", e.message)
        } catch (e: Exception) {
            log.warn("Pix event {} failed, will be retried: {}", message.messageId(), e.toString())
        }
    }

    /** Only the two results of an outgoing Pix matter here; every other event type is someone else's. */
    fun handle(event: PixEventMessage) {
        val settled = when (event.type) {
            "PIX_SENT_COMPLETED" -> true
            "PIX_SENT_REFUNDED" -> false
            else -> return
        }
        val requestId = event.requestId ?: return
        val (tenant, outcome) = executions.onPixEvent(PixOutcome(requestId, settled, event.reasonCode)) ?: return
        MDC.putCloseable("tenant_id", tenant.toString()).use {
            log.info("Pix {} {} for attempt {}: {}", event.endToEndId, event.type, requestId, outcome)
        }
    }

    /** The queue may not exist yet while the bus is being set up (LocalStack init): wait for it. */
    private fun queueUrl(): String? {
        while (running) {
            try {
                return sqs.getQueueUrl { it.queueName(queueName) }.queueUrl()
            } catch (e: Exception) {
                log.info("Queue {} not available yet ({}), retrying", queueName, e.javaClass.simpleName)
                sleep(Duration.ofSeconds(2))
            }
        }
        return null
    }

    private fun sleep(d: Duration) = try {
        Thread.sleep(d)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}
