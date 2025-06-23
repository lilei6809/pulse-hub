package com.pulsehub.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a user activity event that is sent through Kafka.
 * This is the data contract for our event-driven communication.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserActivityEvent {

    /**
     * Unique identifier for the event.
     */
    private UUID eventId;

    /**
     * Identifier for the user who performed the action.
     */
    private String userId;

    /**
     * Type of the event, e.g., "page_view", "add_to_cart", "purchase".
     */
    private String eventType;

    /**
     * Source of the event, e.g., "web_app", "mobile_app", "backend_service".
     */
    private String eventSource;

    /**
     * Timestamp when the event occurred.
     */
    private Instant timestamp;

    /**
     * IP address of the user.
     */
    private String ipAddress;

    /**
     * User agent string from the client.
     */
    private String userAgent;

    /**
     * A flexible field to store any additional data related to the event,
     * in a key-value format.
     */
    private Map<String, Object> payload;
} 