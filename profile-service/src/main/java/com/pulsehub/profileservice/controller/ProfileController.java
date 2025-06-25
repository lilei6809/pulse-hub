package com.pulsehub.profileservice.controller;

import com.pulsehub.profileservice.controller.dto.CreateProfileRequest;
import com.pulsehub.profileservice.entity.UserProfile;
import com.pulsehub.profileservice.service.ProfileService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/profiles")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @PostMapping
    public ResponseEntity<UserProfile> createProfile(@RequestBody CreateProfileRequest request) {
        UserProfile newProfile = profileService.createProfile(request.getUserId());
        return new ResponseEntity<>(newProfile, HttpStatus.CREATED);
    }

    @GetMapping("/{userId}")
    public ResponseEntity<UserProfile> getProfileById(@PathVariable String userId) {
        return profileService.findProfileById(userId)
                .map(ResponseEntity::ok) // If profile is found, return 200 OK with the profile
                .orElse(ResponseEntity.notFound().build()); // If not found, return 404 Not Found
    }
} 