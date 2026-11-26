package dev.llmgateway.gateway.usage;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/** How many usage events were sent to Kafka, lost, and are waiting (design note 38). */
class UsageSinkMetrics implements MeterBinder {

    private final KafkaUsageSink sink;

    UsageSinkMetrics(KafkaUsageSink sink) {
        this.sink = sink;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        FunctionCounter.builder("usage.events.sent", sink, KafkaUsageSink::sent)
                .description("Usage events Kafka acknowledged").register(registry);
        FunctionCounter.builder("usage.events.dropped", sink, KafkaUsageSink::dropped)
                .description("Usage events lost").register(registry);
        Gauge.builder("usage.events.queued", sink, KafkaUsageSink::queued)
                .description("Usage events waiting in memory").register(registry);
    }
}
