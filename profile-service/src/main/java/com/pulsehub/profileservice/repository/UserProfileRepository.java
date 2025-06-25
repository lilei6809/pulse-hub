package com.pulsehub.profileservice.repository;

import com.pulsehub.profileservice.entity.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserProfileRepository extends JpaRepository<UserProfile, String> {
    // Spring Data JPA will automatically provide implementations for standard CRUD operations.
    // We can add custom query methods here if needed in the future.
} 