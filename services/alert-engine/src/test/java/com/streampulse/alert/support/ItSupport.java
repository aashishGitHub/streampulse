/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.support;

import com.streampulse.contracts.v1.AlertEvent;
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
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/** Kafka helpers shared by the integration tests; the containers are declared per test class. */
public final class ItSupport {

    public record Alert(String key, AlertEvent event) {}

    private ItSupport() {}

    public static void createTopic(String bootstrap, String name, int partitions, Map<String, String> configs)
            throws Exception {
        try (var admin = AdminClient.create(Map.<String, Object>of("bootstrap.servers", bootstrap))) {
            admin.createTopics(List.of(new NewTopic(name, partitions, (short) 1).configs(configs)))
                    .all()
                    .get(30, TimeUnit.SECONDS);
        }
    }

    public static void send(String bootstrap, String topic, String key, byte[] value) throws Exception {
        var props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        try (var producer = new KafkaProducer<String, byte[]>(props)) {
            producer.send(new ProducerRecord<>(topic, key, value)).get(30, TimeUnit.SECONDS);
        }
    }

    /** Everything on alerts.v1 right now, decoded, in partition order. */
    public static List<Alert> readAlerts(String bootstrap, String topic) {
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
            var alerts = new ArrayList<Alert>();
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (partitions.stream().anyMatch(tp -> consumer.position(tp) < ends.get(tp))) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("did not reach the end offsets within 20s: " + ends);
                }
                consumer.poll(Duration.ofMillis(200))
                        .forEach(r -> alerts.add(new Alert(r.key(), Wire.alert(r.value()))));
            }
            return alerts;
        }
    }

    /** True when the group has committed everything currently on the topic. */
    public static boolean groupCaughtUp(String bootstrap, String group, String topic) throws Exception {
        try (var admin = AdminClient.create(Map.<String, Object>of("bootstrap.servers", bootstrap))) {
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(group)
                    .partitionsToOffsetAndMetadata()
                    .get(30, TimeUnit.SECONDS);
            var props = new Properties();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
            try (var consumer = new KafkaConsumer<String, byte[]>(props)) {
                var partitions = consumer.partitionsFor(topic).stream()
                        .map(i -> new TopicPartition(topic, i.partition()))
                        .toList();
                Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);
                return partitions.stream().allMatch(tp -> {
                    OffsetAndMetadata c = committed.get(tp);
                    return ends.get(tp) == 0 || (c != null && c.offset() >= ends.get(tp));
                });
            }
        }
    }
}
