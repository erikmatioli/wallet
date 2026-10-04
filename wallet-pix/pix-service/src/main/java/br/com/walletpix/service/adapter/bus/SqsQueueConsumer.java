package br.com.walletpix.service.adapter.bus;

import br.com.walletpix.service.application.MessageFailures.PermanentFailure;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

/**
 * Long-polls one SQS queue and hands each message to a handler, on virtual threads.
 * <ul>
 *   <li>Success: the message is deleted - only after the handler returned, i.e. after its
 *       transaction committed.</li>
 *   <li>{@link PermanentFailure}: made visible again immediately, so the queue's redrive policy
 *       moves it to the DLQ after maxReceiveCount attempts instead of waiting out the timeout.</li>
 *   <li>Anything else: left alone; it reappears after the visibility timeout and is retried.</li>
 * </ul>
 */
public final class SqsQueueConsumer implements SmartLifecycle {

    @FunctionalInterface
    public interface Handler {
        void handle(String body);
    }

    private static final Logger log = LoggerFactory.getLogger(SqsQueueConsumer.class);

    private final SqsClient sqs;
    private final String queueName;
    private final Handler handler;
    private volatile boolean running;
    private Thread poller;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public SqsQueueConsumer(SqsClient sqs, String queueName, Handler handler) {
        this.sqs = sqs;
        this.queueName = queueName;
        this.handler = handler;
    }

    @Override
    public void start() {
        running = true;
        poller = Thread.ofVirtual().name("sqs-" + queueName).start(this::loop);
    }

    @Override
    public void stop() {
        running = false;
        if (poller != null) {
            poller.interrupt();
        }
        workers.shutdown();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        String url = resolveQueueUrl();
        while (running) {
            try {
                List<Message> messages = sqs.receiveMessage(b -> b.queueUrl(url)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(20)
                        .messageAttributeNames("All")).messages();
                List<Future<?>> inFlight = messages.stream()
                        .<Future<?>>map(m -> workers.submit(() -> process(url, m)))
                        .toList();
                for (Future<?> f : inFlight) {
                    f.get();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (running) {
                    log.warn("Polling {} failed, retrying: {}", queueName, e.toString());
                    sleep(Duration.ofSeconds(2));
                }
            }
        }
    }

    private void process(String url, Message message) {
        Map<String, String> attributes = new HashMap<>();
        message.messageAttributes().forEach((k, v) -> attributes.put(k, stringValue(v)));
        Span span = TraceContext.startConsumerSpan(queueName + " process", attributes.get(TraceContext.ATTRIBUTE));
        try (Scope ignored = span.makeCurrent()) {
            handler.handle(message.body());
            sqs.deleteMessage(b -> b.queueUrl(url).receiptHandle(message.receiptHandle()));
        } catch (PermanentFailure e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            log.error("Message {} on {} cannot be processed, sending towards the DLQ: {}", message.messageId(),
                    queueName, e.getMessage());
            sqs.changeMessageVisibility(b -> b.queueUrl(url).receiptHandle(message.receiptHandle()).visibilityTimeout(0));
        } catch (RuntimeException e) {
            span.setStatus(StatusCode.ERROR, e.toString());
            log.warn("Message {} on {} failed, will be retried: {}", message.messageId(), queueName, e.toString());
        } finally {
            span.end();
        }
    }

    /** The queue may not exist yet when the bus is still being provisioned (LocalStack init): wait for it. */
    private String resolveQueueUrl() {
        while (running) {
            try {
                return sqs.getQueueUrl(b -> b.queueName(queueName)).queueUrl();
            } catch (RuntimeException e) {
                log.info("Queue {} not available yet ({}), retrying", queueName, e.getClass().getSimpleName());
                sleep(Duration.ofSeconds(2));
            }
        }
        throw new IllegalStateException("stopped before queue " + queueName + " was available");
    }

    private static String stringValue(MessageAttributeValue v) {
        return v.stringValue();
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
