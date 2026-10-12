/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.adapter.out.kafka;

import com.streampulse.sink.application.batch.DeadLetterSink;
import com.streampulse.sink.domain.AggregateRecord;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;

/** Parks the original bytes and key, untouched, with the reason in a header (api/asyncapi: telemetryAggDlt). */
public final class KafkaDeadLetterSink implements DeadLetterSink<AggregateRecord> {

    static final String REASON_HEADER = "dlt-reason";

    private final KafkaTemplate<String, byte[]> template;
    private final String topic;
    private final Duration sendTimeout;

    public KafkaDeadLetterSink(KafkaTemplate<String, byte[]> template, String topic, Duration sendTimeout) {
        this.template = template;
        this.topic = topic;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public void deadLetter(AggregateRecord record, String reason) throws Exception {
        var out = new ProducerRecord<String, byte[]>(topic, record.key(), record.value());
        out.headers().add(REASON_HEADER, reason.getBytes(StandardCharsets.UTF_8));
        // Waits for the broker: a record is only "parked" once the DLT has it.
        template.send(out).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }
}
