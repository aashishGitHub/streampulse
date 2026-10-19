/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.adapter.out.sqs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streampulse.alert.application.AlertChannel;
import com.streampulse.alert.application.AlertView;
import com.streampulse.alert.application.DeliveryException;
import com.streampulse.alert.domain.Alert;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * Sends each alert change as a JSON message (the same shape as the REST list) to a standard queue.
 *
 * <p>Standard queues are at-least-once and unordered, which matches the rest of the delivery
 * chain; a consumer de-duplicates on {@code alertId} + {@code state}. The queue is created on demand:
 * {@code CreateQueue} with the same name and no attributes returns the existing queue, so calling it
 * at start-up and again lazily is idempotent. Local simulation proves the API calls only (ADR-0003).
 */
public final class SqsAlertChannel implements AlertChannel {

    private final SqsClient sqs;
    private final String queueName;
    private final ObjectMapper mapper;
    private volatile String queueUrl;

    public SqsAlertChannel(SqsClient sqs, String queueName, ObjectMapper mapper) {
        this.sqs = sqs;
        this.queueName = queueName;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "sqs";
    }

    /** Creates the queue if needed and remembers its URL. Safe to call repeatedly. */
    public String ensureQueue() {
        String url = queueUrl;
        if (url == null) {
            url = sqs.createQueue(
                            CreateQueueRequest.builder().queueName(queueName).build())
                    .queueUrl();
            queueUrl = url;
        }
        return url;
    }

    @Override
    public void deliver(Alert alert) {
        try {
            String body = mapper.writeValueAsString(AlertView.from(alert));
            sqs.sendMessage(SendMessageRequest.builder()
                    .queueUrl(ensureQueue())
                    .messageBody(body)
                    .build());
        } catch (JsonProcessingException | SdkException e) {
            throw new DeliveryException("could not send alert " + alert.alertId() + " to queue " + queueName, e);
        }
    }
}
