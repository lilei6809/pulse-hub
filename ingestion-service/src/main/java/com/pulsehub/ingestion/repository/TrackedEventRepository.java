package com.pulsehub.ingestion.repository;

import com.pulsehub.ingestion.entity.TrackedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Spring Data JPA repository for the TrackedEvent entity.
 * Provides CRUD operations out of the box.
 */
@Repository
public interface TrackedEventRepository extends JpaRepository<TrackedEvent, UUID> {
} 