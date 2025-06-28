package com.pulsehub.profileservice.controller;

import com.pulsehub.profileservice.controller.dto.CreateProfileRequest;
import com.pulsehub.profileservice.entity.UserProfile;
import com.pulsehub.profileservice.service.ProfileService;
import org.apache.kafka.common.errors.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/profiles")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @PostMapping
    public ResponseEntity<UserProfile> createProfile(@RequestBody CreateProfileRequest request) {
        UserProfile userProfile = UserProfile.builder()
                .userId(request.getUserId())
                .createdAt(LocalDateTime.now())
                .lastSeenAt(LocalDateTime.now())
                .build();
        UserProfile createdProfile = profileService.createProfile(userProfile);
        return new ResponseEntity<>(createdProfile, HttpStatus.CREATED);
    }

    @GetMapping("/{userId}")
    public ResponseEntity<UserProfile> getProfileById(@PathVariable String userId) {
        // 2. 从service获取Optional对象
        UserProfile userProfile = profileService.getProfileByUserId(userId)
                // 3. 如果找不到，就抛出我们之前定义的异常
                .orElseThrow(() -> new ResourceNotFoundException("User profile not found with id: " + userId));
        return ResponseEntity.ok(userProfile);
    }

    @GetMapping("/exists/{userId}")
    public ResponseEntity<Boolean> checkIfProfileExists(@PathVariable String userId) {
        boolean exists = profileService.profileExists(userId);
        return ResponseEntity.ok(exists);
    }
} 