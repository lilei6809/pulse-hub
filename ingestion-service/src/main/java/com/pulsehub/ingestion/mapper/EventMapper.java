package com.pulsehub.ingestion.mapper;

import com.pulsehub.common.model.UserActivityEvent;
import com.pulsehub.ingestion.entity.TrackedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * Utility class to map between different event models.
 */
public final class EventMapper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Private constructor to prevent instantiation
    private EventMapper() {}

    /**
     * Maps a UserActivityEvent (from Kafka) to a TrackedEvent entity (for DB).
     *
     * @param event the UserActivityEvent to map.
     * @return a new TrackedEvent entity.
     */
    public static TrackedEvent toEntity(UserActivityEvent event) {
        if (event == null) {
            return null;
        }

        String payloadAsString = null;
        if (event.getPayload() != null && !event.getPayload().isEmpty()) {
            try {
                // We serialize the payload map to a JSON string for storage in a TEXT column.
                payloadAsString = OBJECT_MAPPER.writeValueAsString(event.getPayload());
            } catch (JsonProcessingException e) {
                // In a real application, you'd want more robust error handling.
                // For now, we'll log it and proceed with a null payload.
                System.err.println("Error serializing event payload: " + e.getMessage());
            }
        }

        return TrackedEvent.builder()
                .eventId(event.getEventId())
                .userId(event.getUserId())
                .eventType(event.getEventType())
                .eventSource(event.getEventSource())
                .timestamp(event.getTimestamp())
                .ipAddress(event.getIpAddress())
                .userAgent(event.getUserAgent())
                .payload(payloadAsString)
                .processedAt(Instant.now()) // Set the processing timestamp
                .build();
    }
} 