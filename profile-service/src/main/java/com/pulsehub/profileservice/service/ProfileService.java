package com.pulsehub.profileservice.service;

import com.pulsehub.profileservice.entity.UserProfile;
import com.pulsehub.profileservice.repository.UserProfileRepository;
import org.springframework.cache.annotation.Cacheable;
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

    public UserProfile createProfile(UserProfile userProfile) {
        return userProfileRepository.save(userProfile);
    }

    /**
     * 根据用户ID获取用户画像。
     * 这个方法现在返回一个Optional，以优雅地处理用户不存在的情况。
     * 当数据库中找不到用户时，方法会返回一个空的Optional，
     * Spring的缓存机制会将这个“空”结果缓存起来，以防止缓存穿透。
     *
     * @param userId 要查询的用户ID
     * @return 包含用户画像的Optional，如果不存在则为空
     */
    @Cacheable(value = "user-profiles", key = "#userId")
    public Optional<UserProfile> getProfileByUserId(String userId) {
        // 2. 直接返回仓库查询的结果，它本身就是一个Optional
        return userProfileRepository.findById(userId);
    }



    public boolean profileExists(String userId) {
        return userProfileRepository.existsById(userId);
    }
} 