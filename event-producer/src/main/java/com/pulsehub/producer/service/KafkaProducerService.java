package com.pulsehub.producer.service;

import com.pulsehub.common.model.UserActivityEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
public class KafkaProducerService {

    private static final Logger logger = LoggerFactory.getLogger(KafkaProducerService.class);

    private final KafkaTemplate<String, UserActivityEvent> kafkaTemplate;
    private final String topicName;

    public KafkaProducerService(
            KafkaTemplate<String, UserActivityEvent> kafkaTemplate,
            @Value("${application.kafka.topics.user-activity}") String topicName) {
        this.kafkaTemplate = kafkaTemplate;
        this.topicName = topicName;
    }

    public void sendEvent(UserActivityEvent event) {
        logger.info("==> Sending event to Kafka: {}", event.getEventId());

        CompletableFuture<SendResult<String, UserActivityEvent>> future =
                kafkaTemplate.send(topicName, event.getUserId(), event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                logger.error("!!! Unable to send message=[{}] due to : {}", event, ex.getMessage(), ex);
            } else {
                logger.info("<== Sent message=[{}] with offset=[{}]", event.getEventId(), result.getRecordMetadata().offset());
            }
        });
    }
} 