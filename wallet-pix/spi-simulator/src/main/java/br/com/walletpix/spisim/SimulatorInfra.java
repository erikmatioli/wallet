package br.com.walletpix.spisim;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/** AWS clients and the queue consumer. Simpler than the Pix service's: no DLQ handling, no retries worth having. */
@Configuration(proxyBeanMethods = false)
class SimulatorInfra {

    private static final Logger log = LoggerFactory.getLogger(SimulatorInfra.class);

    @Bean(destroyMethod = "close")
    SqsClient sqsClient(SimulatorProperties p) {
        return SqsClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).region(Region.of(p.region()))
                .endpointOverride(p.endpoint())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(p.accessKey(), p.secretKey())))
                .build();
    }

    @Bean(destroyMethod = "close")
    SnsClient snsClient(SimulatorProperties p) {
        return SnsClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).region(Region.of(p.region()))
                .endpointOverride(p.endpoint())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(p.accessKey(), p.secretKey())))
                .build();
    }

    @Bean
    SmartLifecycle inboundConsumer(SqsClient sqs, SimulatorProperties p, SpiSimulator simulator) {
        return new SmartLifecycle() {
            private volatile boolean running;
            private Thread thread;

            @Override
            public void start() {
                running = true;
                thread = Thread.ofVirtual().name("spi-inbound").start(() -> loop(sqs, p.inboundQueue(), simulator));
            }

            @Override
            public void stop() {
                running = false;
                thread.interrupt();
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            private void loop(SqsClient sqs, String queue, SpiSimulator simulator) {
                String url = null;
                while (running) {
                    try {
                        if (url == null) {
                            url = sqs.getQueueUrl(b -> b.queueName(queue)).queueUrl();
                        }
                        String queueUrl = url;
                        for (Message m : sqs.receiveMessage(b -> b.queueUrl(queueUrl).maxNumberOfMessages(10)
                                .waitTimeSeconds(20).messageAttributeNames("All")).messages()) {
                            handle(sqs, queueUrl, m, simulator);
                        }
                    } catch (RuntimeException e) {
                        if (!running) {
                            return;
                        }
                        log.info("simulator queue {} not ready or polling failed ({}), retrying", queue, e.toString());
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException ie) {
                            return;
                        }
                    }
                }
            }
        };
    }

    private static void handle(SqsClient sqs, String url, Message m, SpiSimulator simulator) {
        var tp = m.messageAttributes().get("traceparent");
        Context parent = tp == null ? Context.root() : W3CTraceContextPropagator.getInstance()
                .extract(Context.root(), Map.of("traceparent", tp.stringValue()), GETTER);
        Span span = GlobalOpenTelemetry.getTracer("spi-simulator").spanBuilder("spi route")
                .setParent(parent).setSpanKind(SpanKind.CONSUMER).startSpan();
        try (Scope ignored = span.makeCurrent()) {
            simulator.onMessageFromPsp(m.body());
        } catch (RuntimeException e) {
            log.error("simulator could not route message {}: {}", m.messageId(), e.toString());
        } finally {
            span.end();
            // Always delete: a message the simulator can't route won't route on a retry either.
            sqs.deleteMessage(b -> b.queueUrl(url).receiptHandle(m.receiptHandle()));
        }
    }

    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return List.copyOf(carrier.keySet());
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };
}
