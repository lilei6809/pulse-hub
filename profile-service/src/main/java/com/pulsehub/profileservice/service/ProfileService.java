package com.pulsehub.profileservice.service;

import com.pulsehub.profileservice.entity.UserProfile;
import com.pulsehub.profileservice.repository.UserProfileRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class ProfileService {

    private final UserProfileRepository userProfileRepository;

    public ProfileService(UserProfileRepository userProfileRepository) {
        this.userProfileRepository = userProfileRepository;
    }

    /**
     * Creates a new user profile if one does not already exist for the given userId.
     *
     * @param userId The ID of the user to create a profile for.
     * @return The newly created UserProfile.
     * @throws IllegalStateException if a profile for the given userId already exists.
     */
    public UserProfile createProfile(String userId) {
        // First, check if a profile for this user already exists to prevent duplicates.
        Optional<UserProfile> existingProfile = userProfileRepository.findById(userId);
        if (existingProfile.isPresent()) {
            // In a real-world scenario, how to handle this could be a business decision.
            // For now, we'll throw an exception to make the behavior explicit.
            throw new IllegalStateException("Profile for user ID " + userId + " already exists.");
        }

        UserProfile newProfile = new UserProfile();
        newProfile.setUserId(userId);
        return userProfileRepository.save(newProfile);
    }

    /**
     * Finds a user profile by its ID.
     *
     * @param userId The ID of the user to find.
     * @return An Optional containing the UserProfile if found, or an empty Optional otherwise.
     */
    public Optional<UserProfile> findProfileById(String userId) {
        return userProfileRepository.findById(userId);
    }
} 