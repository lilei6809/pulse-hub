package com.pulsehub.ingestion.service;

import com.pulsehub.common.proto.UserActivityEvent;
import com.pulsehub.ingestion.entity.TrackedEvent;
import com.pulsehub.ingestion.mapper.EventMapper;
import com.pulsehub.ingestion.repository.TrackedEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class KafkaConsumerService {

    private final TrackedEventRepository trackedEventRepository;

    public KafkaConsumerService(TrackedEventRepository trackedEventRepository) {
        this.trackedEventRepository = trackedEventRepository;
    }

    @KafkaListener(topics = "${pulsehub.kafka.topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(UserActivityEvent event) {
        log.info("Consumed event: {}", event.getEventId());
        TrackedEvent trackedEvent = EventMapper.toTrackedEvent(event);
        trackedEventRepository.save(trackedEvent);
        log.info("Saved tracked event to database with ID: {}", trackedEvent.getId());
    }
}