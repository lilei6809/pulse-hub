package com.pulsehub.producer.util;

import com.pulsehub.common.proto.UserActivityEvent;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Utility to generate sample UserActivityEvent objects.
 */
public class EventGenerator {

    /**
     * Generates a sample UserActivityEvent with randomized data.
     *
     * @return A new UserActivityEvent Protobuf object.
     */
    public static UserActivityEvent generateEvent() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long timestamp = System.currentTimeMillis();
        String eventId = UUID.randomUUID().toString();
        String userId = "user-" + random.nextInt(1, 1001);
        String sessionId = UUID.randomUUID().toString();

        return UserActivityEvent.newBuilder()
                .setEventId(eventId)
                .setUserId(userId)
                .setSessionId(sessionId)
                .setEventType("page_view")
                .setTimestamp(timestamp)
                .setSourceIp("192.168.1." + random.nextInt(1, 255))
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .setPageUrl("/products/item/" + random.nextInt(1, 1001))
                .setProductId("prod-" + random.nextInt(1, 101))
                .setCategoryId("cat-" + random.nextInt(1, 11))
                .setPrice(random.nextDouble(10.0, 500.0))
                .setQuantity(random.nextInt(1, 5))
                .build();
    }
} 