package com.pulsehub.ingestion.mapper;

import com.pulsehub.common.proto.UserActivityEvent;
import com.pulsehub.ingestion.entity.TrackedEvent;

/**
 * Utility class to map between different event models.
 */
public class EventMapper {

    /**
     * Maps a UserActivityEvent (from Kafka, Protobuf format) to a TrackedEvent entity (for database).
     *
     * @param event The UserActivityEvent object received from Kafka.
     * @return A TrackedEvent entity ready to be saved to the database.
     */
    public static TrackedEvent toTrackedEvent(UserActivityEvent event) {
        if (event == null) {
            return null;
        }

        TrackedEvent trackedEvent = new TrackedEvent();

        // The 'id' and 'processedAt' fields are auto-generated by the database.
        // We map all other fields from the Protobuf object.
        trackedEvent.setEventId(event.getEventId());
        trackedEvent.setUserId(event.getUserId());
        trackedEvent.setSessionId(event.getSessionId());
        trackedEvent.setEventType(event.getEventType());
        trackedEvent.setTimestamp(event.getTimestamp());
        trackedEvent.setSourceIp(event.getSourceIp());
        trackedEvent.setUserAgent(event.getUserAgent());
        trackedEvent.setPageUrl(event.getPageUrl());
        trackedEvent.setProductId(event.getProductId());
        trackedEvent.setCategoryId(event.getCategoryId());
        trackedEvent.setPrice(event.getPrice());
        trackedEvent.setQuantity(event.getQuantity());

        return trackedEvent;
    }
} 