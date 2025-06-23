package com.pulsehub.producer;

import com.pulsehub.producer.service.KafkaProducerService;
import com.pulsehub.producer.util.EventGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@SpringBootApplication
@EnableScheduling
public class EventProducerApplication {

    private static final Logger logger = LoggerFactory.getLogger(EventProducerApplication.class);

    private final KafkaProducerService producerService;
    private final EventGenerator eventGenerator;

    public EventProducerApplication(KafkaProducerService producerService, EventGenerator eventGenerator) {
        this.producerService = producerService;
        this.eventGenerator = eventGenerator;
    }

    public static void main(String[] args) {
        SpringApplication.run(EventProducerApplication.class, args);
    }

    @Scheduled(fixedRate = 5000) // 5000 milliseconds = 5 seconds
    public void generateAndSendEvent() {
        logger.info("--- Scheduling event generation ---");
        producerService.sendEvent(eventGenerator.generateRandomEvent());
    }
} 