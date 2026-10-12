/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor.adapter.streams;

import com.streampulse.processor.domain.ProcessorMetrics;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.streams.processor.api.FixedKeyProcessor;
import org.apache.kafka.streams.processor.api.FixedKeyProcessorContext;
import org.apache.kafka.streams.processor.api.FixedKeyRecord;

/** Turns a rejected {@link Decoded} back into its original bytes, with the reason in headers. */
final class DltRecorder implements FixedKeyProcessor<String, Decoded, byte[]> {

    private final String originalTopic;
    private final ProcessorMetrics metrics;
    private FixedKeyProcessorContext<String, byte[]> context;

    DltRecorder(String originalTopic, ProcessorMetrics metrics) {
        this.originalTopic = originalTopic;
        this.metrics = metrics;
    }

    @Override
    public void init(FixedKeyProcessorContext<String, byte[]> context) {
        this.context = context;
    }

    @Override
    public void process(FixedKeyRecord<String, Decoded> record) {
        Decoded rejected = record.value();
        metrics.rejected(rejected.rejection());

        Headers headers = record.headers();
        headers.add(DltHeaders.REASON, utf8(rejected.rejection().name()));
        headers.add(DltHeaders.ORIGINAL_TOPIC, utf8(originalTopic));
        context.recordMetadata().ifPresent(meta -> {
            headers.add(DltHeaders.ORIGINAL_PARTITION, utf8(Integer.toString(meta.partition())));
            headers.add(DltHeaders.ORIGINAL_OFFSET, utf8(Long.toString(meta.offset())));
        });

        context.forward(record.withValue(rejected.raw()));
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
