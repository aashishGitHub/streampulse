/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Kafka helpers shared by the integration tests; the containers themselves are declared per test class. */
public final class ItSupport {

    private ItSupport() {}

    public static void createTopics(String bootstrap, Map<String, Integer> partitionsByTopic) throws Exception {
        try (var admin = AdminClient.create(Map.<String, Object>of("bootstrap.servers", bootstrap))) {
            var topics = new ArrayList<NewTopic>();
            partitionsByTopic.forEach((name, partitions) -> topics.add(new NewTopic(name, partitions, (short) 1)));
            admin.createTopics(topics).all().get(30, TimeUnit.SECONDS);
        }
    }

    /** Sum over partitions of the group's committed offset: the number of records it has consumed and acknowledged. */
    public static long committedOffsetTotal(String bootstrap, String group) throws Exception {
        try (var admin = AdminClient.create(Map.<String, Object>of("bootstrap.servers", bootstrap))) {
            Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(group)
                    .partitionsToOffsetAndMetadata()
                    .get(30, TimeUnit.SECONDS);
            return offsets.values().stream()
                    .mapToLong(OffsetAndMetadata::offset)
                    .sum();
        }
    }

    /** Everything on the topic right now, from every partition, up to its end offset. */
    public static List<ConsumerRecord<String, byte[]>> readAll(String bootstrap, String topic) {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        try (var consumer = new KafkaConsumer<String, byte[]>(props)) {
            var infos = consumer.partitionsFor(topic);
            if (infos == null) {
                return List.of();
            }
            List<TopicPartition> partitions = infos.stream()
                    .map(i -> new TopicPartition(topic, i.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);
            var records = new ArrayList<ConsumerRecord<String, byte[]>>();
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (partitions.stream().anyMatch(tp -> consumer.position(tp) < ends.get(tp))) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("did not reach the end offsets within 20s: " + ends);
                }
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            return records;
        }
    }
}
