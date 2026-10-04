package br.com.walletpix.service.adapter.bus;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * W3C trace context across the bus. The outbox relay publishes from a scheduler thread, long
 * after the request that produced the message, so the context is captured when the message is
 * enqueued, stored next to it, sent as the {@code traceparent} message attribute, and restored
 * by the consumer - one trace from the SPI message to the credit, through every queue.
 */
public final class TraceContext {

    public static final String ATTRIBUTE = "traceparent";

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

    private TraceContext() {
    }

    /** The current context as a traceparent header value, or null when there is no active trace. */
    public static String currentTraceparent() {
        Map<String, String> carrier = new HashMap<>();
        W3CTraceContextPropagator.getInstance().inject(Context.current(), carrier, Map::put);
        return carrier.get(ATTRIBUTE);
    }

    /** Starts a CONSUMER span, child of {@code traceparent} when present. The caller ends it. */
    public static Span startConsumerSpan(String name, String traceparent) {
        Context parent = traceparent == null ? Context.root()
                : W3CTraceContextPropagator.getInstance().extract(Context.root(), Map.of(ATTRIBUTE, traceparent), GETTER);
        return GlobalOpenTelemetry.getTracer("wallet-pix").spanBuilder(name)
                .setParent(parent)
                .setSpanKind(SpanKind.CONSUMER)
                .startSpan();
    }
}
