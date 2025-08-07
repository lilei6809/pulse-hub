package com.pulsehub.profileservice.controller;

import com.pulsehub.profileservice.controller.dto.CreateDynamicUserProfileRequest;
import com.pulsehub.profileservice.controller.dto.IncPageViewsRequest;
import com.pulsehub.profileservice.domain.DeviceClass;
import com.pulsehub.profileservice.domain.DeviceClassifier;
import com.pulsehub.profileservice.domain.DynamicUserProfile;
import com.pulsehub.profileservice.service.DynamicProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/v1/dynamic_profiles")
public class DynamicUserProfileController {

    private final DynamicProfileService dynamicProfileService;

    private final DeviceClassifier deviceClassifier;

    public DynamicUserProfileController(DynamicProfileService dynamicProfileService, DeviceClassifier deviceClassifier) {
        this.dynamicProfileService = dynamicProfileService;
        this.deviceClassifier = deviceClassifier;
    }

    @PostMapping
    public ResponseEntity<DynamicUserProfile> createDynamicUserProfile(@RequestBody CreateDynamicUserProfileRequest request) {
        DeviceClass deviceClass = (deviceClassifier.classify(request.getDevice()));

        String userId = request.getUserId();

        if (userId == null || userId.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        DynamicUserProfile profile = DynamicUserProfile.builder()
                .userId(request.getUserId())
                .pageViewCount(request.getPageViewCount())
                .deviceClassification(deviceClass)
                .build();

//        profile.getRecentDeviceTypes().add(deviceClass);

        DynamicUserProfile dynamicUserProfile = null;


        if (dynamicProfileService.profileExists(userId)) {
            dynamicUserProfile = dynamicProfileService.updateProfile(profile);
        } else {
            dynamicUserProfile = dynamicProfileService.createProfile(profile);
        }

        return ResponseEntity.ok().body(dynamicUserProfile);

    }

    @GetMapping("/{userId}")
    public ResponseEntity<DynamicUserProfile> getDynamicUserProfile(@PathVariable String userId) {
        Optional<DynamicUserProfile> profile = dynamicProfileService.getProfile(userId);

        if (profile.isPresent()) {
            return ResponseEntity.ok().body(profile.get());
        } else  {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{userId}/{pageViews}")
    public ResponseEntity<DynamicUserProfile> increasePageViews(@PathVariable String userId, @PathVariable String pageViews) {
        DynamicUserProfile updatedProfile = dynamicProfileService.recordPageViews(userId, Long.parseLong(pageViews));

        return ResponseEntity.ok().body(updatedProfile);
    }
}
