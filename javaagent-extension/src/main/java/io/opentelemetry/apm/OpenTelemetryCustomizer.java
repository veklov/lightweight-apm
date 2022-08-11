package io.opentelemetry.apm;

import com.google.auto.service.AutoService;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

@AutoService(AutoConfigurationCustomizerProvider.class)
public class OpenTelemetryCustomizer implements AutoConfigurationCustomizerProvider {

    //  private static final AttributeKey<String> SERVICE_NAME =
    // AttributeKey.stringKey("service.name");
    //
    //  private String serviceName;

    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
    /*
      // Prometheus exporter does not expose Resource attributes,
      // and Prometheus exporter is a reader (not an exporter) and cannot be customized
      // View's functionality to add attributes is private (although, partially exposed through
      // SdlMeterProviderUtil)
      // so we have to put service.name to metric attribute ourselves and for that we need access to
      // the resource
      autoConfiguration.addResourceCustomizer((resource, configProperties) -> {
        serviceName = resource.getAttribute(SERVICE_NAME);
        return resource;
      });
    */
        autoConfiguration.addTracerProviderCustomizer(
                (tracerProvider, config) ->
                        tracerProvider.addSpanProcessor(new SpanToMetricConverter(/*serviceName*/)));
    }

    public static class SpanToMetricConverter implements SpanProcessor {
        private static final AttributeKey<String> TRANSACTION_NAME =
                AttributeKey.stringKey("transaction.name");
        private static final AttributeKey<String> SPAN_NAME = AttributeKey.stringKey("span.name");
        private static final AttributeKey<String> SPAN_KIND = AttributeKey.stringKey("span.kind");
        private static final AttributeKey<String> HTTP_TARGET = AttributeKey.stringKey("http.target");

        private final ChildSpansTracker childSpansTracker = new ChildSpansTracker();
        private final AtomicReference<DoubleHistogram> durationHistogram = new AtomicReference<>();
        // private final String serviceName;

        public SpanToMetricConverter(/*String serviceName*/) {
            // this.serviceName = serviceName;
        }

        @Override
        public boolean isStartRequired() {
            return true;
        }

        @Override
        public void onStart(Context parentContext, ReadWriteSpan span) {
            switch (span.getKind()) {
                case SERVER, CONSUMER -> {
                    // TODO SERVER spans are renamed based on their HTTP_TARGET,
                    //  we need to pass span id to children, store reference to the span,
                    //  and use this span name when child spans finish
                    String transactionName = span.getAttribute(HTTP_TARGET);
                    if (transactionName == null) transactionName = span.getName();
                    span.setAttribute(TRANSACTION_NAME, transactionName);
                }
                default -> {
                    ReadableSpan parentSpan = (ReadableSpan) Span.fromContext(parentContext);
                    if (parentSpan != null) {
                        String transactionName = parentSpan.getAttribute(TRANSACTION_NAME);
                        if (transactionName != null) {
                            span.setAttribute(TRANSACTION_NAME, transactionName);
                        }
                    }
                }
            }
        }

        @Override
        public boolean isEndRequired() {
            return true;
        }

        @Override
        public void onEnd(ReadableSpan span) {
            double spanDurationMillis = span.getLatencyNanos() / 1000000d;
            childSpansTracker.trackAsChildIfNecessary(span, spanDurationMillis);
            getDurationMillisHistogram()
                    .record(
                            childSpansTracker.subtractChildrenIfNecessary(span, spanDurationMillis),
                            extractAttributes(span));
            // TODO report max duration
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode forceFlush() {
            return CompletableResultCode.ofSuccess();
        }

        private Attributes extractAttributes(ReadableSpan span) {
            AttributesBuilder attributes = span.toSpanData().getAttributes().toBuilder();

            Set<String> exclude =
                    Set.of(
                            "thread.id",
                            "thread.name",
                            "net.peer.name",
                            "net.peer.ip",
                            "net.peer.port",
                            "net.transport");
            attributes.removeIf(attributeKey -> exclude.contains(attributeKey.getKey()));

            //      attributes.put(SERVICE_NAME, serviceName);
            attributes.put(SPAN_NAME, span.getName());
            attributes.put(SPAN_KIND, span.getKind().name());
            // TODO add explicit attribute for top level SERVER, CONSUMER spans to make metrics
            //  charting/querying easier?

            return attributes.build();
        }

        private DoubleHistogram getDurationMillisHistogram() {
            DoubleHistogram histogram = durationHistogram.get();
            if (histogram != null) return histogram;
            return durationHistogram.updateAndGet(
                    prevHistogram -> prevHistogram != null ? prevHistogram : createDurationMillisHistogram());
        }

        private DoubleHistogram createDurationMillisHistogram() {
            return GlobalOpenTelemetry.get()
                    .getMeter(OpenTelemetryCustomizer.class.getName())
                    .histogramBuilder("span_duration_millis")
                    .setUnit("ms")
                    .build();
        }
    }

    private record SpanKey(String traceId, String spanId) {
        private static SpanKey of(SpanContext spanContext) {
            return new SpanKey(spanContext.getTraceId(), spanContext.getSpanId());
        }
    }

    private static class ChildSpansTracker {
        private final ConcurrentMap<SpanKey, Double> childSpansDuration = new ConcurrentHashMap<>();

        public void trackAsChildIfNecessary(ReadableSpan span, double spanDurationMillis) {
            SpanContext parentSpanContext = span.getParentSpanContext();
            if (parentSpanContext.isValid()) {
                childSpansDuration.compute(
                        SpanKey.of(parentSpanContext),
                        (unused, oldDuration) -> (oldDuration != null ? oldDuration : 0) + spanDurationMillis);
            }
        }

        public double subtractChildrenIfNecessary(ReadableSpan span, double spanDuration) {
            SpanContext parentSpanContext = span.getParentSpanContext();
            SpanContext spanContext = span.getSpanContext();

            Double childSpansDuration = this.childSpansDuration.remove(SpanKey.of(spanContext));

            if (!parentSpanContext.isValid() || childSpansDuration == null) return spanDuration;

            // TODO parallel executions... Ask developers to ...
            //  1. wrap parallel activity into a proxy span without any other activity, so outer span own
            //     time calculation is correct?
            //  2. mark the proxy span attribute "parallel"=true and report each child span as a separate
            //     sub-transaction with name = <proxy's transaction_name> '>' <child's span_name>
            if (spanDuration < childSpansDuration) return 0.0d;

            return spanDuration - childSpansDuration;
        }
    }
}
