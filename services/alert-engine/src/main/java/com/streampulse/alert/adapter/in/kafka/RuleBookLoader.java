/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.adapter.in.kafka;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the rule book equal to the compacted config topic.
 *
 * <p>It is a table loader, not a consumer group member: it assigns every partition itself,
 * reads from the beginning, never commits, and so every instance holds the whole book. It records the
 * end offsets at start-up and reports {@code loaded} once it has read up to them; the aggregate
 * listener is started only then, so no aggregate is evaluated against a half-loaded book.
 * After that it keeps following the topic on the same thread.
 *
 * <p>If the consumer fails it is rebuilt and re-reads from the beginning, which is safe because
 * applying a record is idempotent.
 */
public final class RuleBookLoader implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RuleBookLoader.class);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(1);

    private final Map<String, Object> consumerConfig;
    private final String topic;
    private final ConfigChangeApplier applier;
    private final Duration pollTimeout;

    private volatile boolean loaded;
    private volatile boolean closing;
    private volatile KafkaConsumer<String, byte[]> consumer;
    private Thread thread;

    public RuleBookLoader(
            Map<String, Object> consumerConfig, String topic, ConfigChangeApplier applier, Duration pollTimeout) {
        this.consumerConfig = Map.copyOf(consumerConfig);
        this.topic = topic;
        this.applier = applier;
        this.pollTimeout = pollTimeout;
    }

    /** @param onLoaded run once, on the loader thread, when the book has caught up with the topic */
    public synchronized void start(Runnable onLoaded) {
        if (thread != null) {
            throw new IllegalStateException("already started");
        }
        thread = Thread.ofPlatform().name("rulebook-loader").daemon().start(() -> loop(onLoaded));
    }

    public boolean isLoaded() {
        return loaded;
    }

    @Override
    public void close() {
        closing = true;
        KafkaConsumer<String, byte[]> active = consumer;
        if (active != null) {
            active.wakeup();
        }
        Thread t;
        synchronized (this) {
            t = thread;
        }
        if (t != null) {
            try {
                t.join(Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void loop(Runnable onLoaded) {
        while (!closing) {
            try (KafkaConsumer<String, byte[]> kafka = new KafkaConsumer<>(consumerConfig)) {
                consumer = kafka;
                follow(kafka, onLoaded);
            } catch (WakeupException e) {
                return;
            } catch (RuntimeException e) {
                if (closing) {
                    return;
                }
                log.error("rule book loader failed, restarting from the beginning", e);
                sleep(RETRY_DELAY);
            }
        }
    }

    private void follow(KafkaConsumer<String, byte[]> kafka, Runnable onLoaded) {
        List<TopicPartition> partitions = awaitPartitions(kafka);
        if (partitions.isEmpty()) {
            return;
        }
        kafka.assign(partitions);
        kafka.seekToBeginning(partitions);
        Map<TopicPartition, Long> end = kafka.endOffsets(partitions);

        boolean notified = loaded;
        while (!closing) {
            for (ConsumerRecord<String, byte[]> record : kafka.poll(pollTimeout)) {
                try {
                    applier.apply(record.key(), record.value());
                } catch (RuntimeException e) {
                    log.error(
                            "failed to apply config record {}-{}@{}",
                            record.topic(),
                            record.partition(),
                            record.offset(),
                            e);
                }
            }
            if (!notified && partitions.stream().allMatch(tp -> kafka.position(tp) >= end.get(tp))) {
                loaded = true;
                notified = true;
                log.info("rule book loaded from {} up to {}", topic, end);
                onLoaded.run();
            }
        }
    }

    private List<TopicPartition> awaitPartitions(KafkaConsumer<String, byte[]> kafka) {
        while (!closing) {
            List<PartitionInfo> infos = kafka.partitionsFor(topic, Duration.ofSeconds(10));
            if (infos != null && !infos.isEmpty()) {
                return infos.stream()
                        .map(info -> new TopicPartition(info.topic(), info.partition()))
                        .toList();
            }
            log.warn("config topic {} does not exist yet, waiting", topic);
            sleep(RETRY_DELAY);
        }
        return List.of();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
