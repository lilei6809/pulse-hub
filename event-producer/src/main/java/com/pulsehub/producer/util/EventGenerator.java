package com.pulsehub.producer.util;

import com.pulsehub.common.model.UserActivityEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class EventGenerator {

    private static final String[] EVENT_TYPES = {"page_view", "add_to_cart", "login", "purchase"};
    private static final String[] EVENT_SOURCES = {"web_app", "mobile_app", "backend_service"};

    public UserActivityEvent generateRandomEvent() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String eventType = EVENT_TYPES[random.nextInt(EVENT_TYPES.length)];

        return UserActivityEvent.builder()
                .eventId(UUID.randomUUID())
                .userId("user-" + random.nextInt(1, 1001))
                .eventType(eventType)
                .eventSource(EVENT_SOURCES[random.nextInt(EVENT_SOURCES.length)])
                .timestamp(Instant.now())
                .ipAddress("192.168.1." + random.nextInt(1, 255))
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .payload(createPayloadFor(eventType, random))
                .build();
    }

    private Map<String, Object> createPayloadFor(String eventType, ThreadLocalRandom random) {
        switch (eventType) {
            case "page_view":
                return Map.of("page", "/products/" + random.nextInt(1, 51));
            case "add_to_cart":
                return Map.of(
                        "productId", "prod-" + random.nextInt(1, 51),
                        "quantity", random.nextInt(1, 6)
                );
            case "purchase":
                return Map.of(
                        "orderId", "order-" + random.nextInt(10000, 99999),
                        "amount", random.nextDouble(5.0, 500.0)
                );
            case "login":
            default:
                return Map.of("status", "success");
        }
    }
} 