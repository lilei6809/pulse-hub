package com.pulsehub.profileservice.controller;

import com.pulsehub.profileservice.controller.dto.CreateStaticProfileRequest;
import com.pulsehub.profileservice.domain.entity.StaticUserProfile;
import com.pulsehub.profileservice.service.StaticProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@Slf4j
@RestController
@RequestMapping("/api/v1/static_profiles")
@RequiredArgsConstructor
public class StaticProfileController {

    private final StaticProfileService staticProfileService;

    @PostMapping
    public ResponseEntity<StaticUserProfile> saveUserProfile(@RequestBody CreateStaticProfileRequest request) {
        StaticUserProfile profile;
        try {
            log.info("📝 收到静态画像创建请求: userId={}",
                    request.getUserId());

            profile = StaticUserProfile.builder()
                    .userId(request.getUserId())
                    .gender(StaticUserProfile.Gender.valueOf(request.getGender()))
                    .city(request.getCity())
                    .phoneNumber(request.getPhoneNumber())
                    .version(1L)
                    .realName(request.getRealName())
                    .registrationDate(Instant.now())
                    .updatedAt(Instant.now())
                    .sourceChannel(request.getSourceChannel())
                    .isDeleted(false)
                    .build();

            staticProfileService.createProfile(profile);

            return  ResponseEntity.ok(profile);
        } catch (IllegalArgumentException e) {
            log.error(e.getMessage());
            return ResponseEntity.badRequest().build();
        }

    }


}
