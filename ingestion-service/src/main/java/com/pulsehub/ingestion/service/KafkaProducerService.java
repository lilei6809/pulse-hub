package com.pulsehub.ingestion.service;

import com.pulsehub.common.proto.UserActivityEvent;
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
            @Value("${pulsehub.kafka.topic:user-activity-events}") String topicName) {
        this.kafkaTemplate = kafkaTemplate;
        this.topicName = topicName;
    }
    
    public CompletableFuture<SendResult<String, UserActivityEvent>> sendEvent(UserActivityEvent event) {
        String key = determinePartitionKey(event);
        
        logger.debug("Sending event to Kafka: topic={}, key={}, messageId={}", 
                topicName, key, event.getEventId());
        
        CompletableFuture<SendResult<String, UserActivityEvent>> future = 
                kafkaTemplate.send(topicName, key, event);
        
        // 异步处理回调
        future.whenComplete((result, ex) -> {
            if (ex != null) {
                logger.error("Failed to send event to Kafka: messageId={}, error={}", 
                        event.getEventId(), ex.getMessage(), ex);
            } else {
                logger.info("Event sent successfully: messageId={}, partition={}, offset={}", 
                        event.getEventId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
        
        return future;
    }
    
    public void sendEventAsync(UserActivityEvent event) {
        sendEvent(event);
    }
    
    public void sendEventSync(UserActivityEvent event) {
        try {
            SendResult<String, UserActivityEvent> result = sendEvent(event).get();
            logger.info("Event sent synchronously: messageId={}, partition={}, offset={}", 
                    event.getEventId(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
        } catch (Exception e) {
            logger.error("Failed to send event synchronously: messageId={}", event.getEventId(), e);
            throw new RuntimeException("Failed to send event to Kafka", e);
        }
    }
    
    private String determinePartitionKey(UserActivityEvent event) {
        // 优先使用 userId 作为分区键以保证同一用户的事件顺序
        if (!event.getUserId().isEmpty()) {
            return event.getUserId();
        }
        
        // 其次使用 anonymousId
        if (!event.getAnonymousId().isEmpty()) {
            return event.getAnonymousId();
        }
        
        // 最后使用 messageId
        return event.getEventId();
    }
}