package com.pulsehub.ingestion.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a user activity event that has been persisted to the database.
 * This is our internal representation of the data at rest.
 */
@Entity
@Table(name = "tracked_events")
@Data
public class TrackedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(nullable = false)
    private String eventId;

    @Column(nullable = false)
    private String userId;

    private String sessionId;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false)
    private long timestamp;

    private String sourceIp;

    @Column(columnDefinition = "TEXT")
    private String userAgent;

    @Column(columnDefinition = "TEXT")
    private String pageUrl;

    private String productId;

    private String categoryId;

    private double price;

    private int quantity;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant processedAt;
} 