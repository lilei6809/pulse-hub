package com.pulsehub.datasync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.EnableEurekaClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

/**
 * Data Synchronization Service - DocSyncProducer
 * 
 * Responsibilities:
 * - Hybrid sync strategy: immediate sync for critical data, batch sync for regular data
 * - Redis distributed locks for atomic operations
 * - Version control and conflict resolution
 * - Kafka message production with priority handling
 * - Scheduled batch processing of dirty flags
 */
@SpringBootApplication
@EnableEurekaClient
@EnableFeignClients
@EnableRetry
@EnableScheduling
public class DataSyncServiceApplication {

    public static void main(String[] args) {
        // Set UTC timezone for consistent time handling
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        
        SpringApplication.run(DataSyncServiceApplication.class, args);
    }
}