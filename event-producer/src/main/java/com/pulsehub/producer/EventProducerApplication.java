package com.pulsehub.producer;

import com.pulsehub.producer.service.KafkaProducerService;
import com.pulsehub.producer.util.EventGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@Slf4j
public class EventProducerApplication {

	public static void main(String[] args) {
		SpringApplication.run(EventProducerApplication.class, args);
	}

	@Bean
	public CommandLineRunner commandLineRunner(KafkaProducerService kafkaProducerService) {
		return args -> {
			log.info("Starting Protobuf event producer...");
			// This loop will run indefinitely, sending a new event every second.
			// In a real application, this would be triggered by actual user actions.
			while (!Thread.currentThread().isInterrupted()) {
				try {
					kafkaProducerService.sendEvent(EventGenerator.generateEvent());
					Thread.sleep(1000); // Wait for 1 second
				} catch (InterruptedException e) {
					log.warn("Event producer thread interrupted.");
					Thread.currentThread().interrupt(); // Preserve the interrupted status
				}
			}
		};
	}
} 