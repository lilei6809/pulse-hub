package com.pulsehub.ingestion.service;

import com.pulsehub.common.model.UserActivityEvent;
import com.pulsehub.ingestion.entity.TrackedEvent;
import com.pulsehub.ingestion.mapper.EventMapper;
import com.pulsehub.ingestion.repository.TrackedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KafkaConsumerService {

    private static final Logger logger = LoggerFactory.getLogger(KafkaConsumerService.class);

    private final TrackedEventRepository repository;

    public KafkaConsumerService(TrackedEventRepository repository) {
        this.repository = repository;
    }

    @KafkaListener(topics = "${application.kafka.topics.user-activity}", groupId = "${spring.kafka.consumer.group-id}")
    @Transactional
    public void consume(UserActivityEvent event) {
        logger.info("==> Received event: {}", event.getEventId());
        try {
            TrackedEvent trackedEvent = EventMapper.toEntity(event);
            repository.save(trackedEvent);
            logger.info("<== Event processed and saved: {}", trackedEvent.getEventId());
        } catch (Exception e) {
            logger.error("!!! Error processing event {}: {}", event.getEventId(), e.getMessage(), e);
            // In a real application, you might move the message to a dead-letter queue (DLQ) here.
        }
    }
} 