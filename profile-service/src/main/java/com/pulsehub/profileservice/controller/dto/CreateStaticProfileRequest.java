package com.pulsehub.profileservice.controller.dto;


import com.pulsehub.profileservice.domain.entity.StaticUserProfile;
import com.pulsehub.profileservice.domain.entity.StaticUserProfile.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
public class CreateStaticProfileRequest {

    private String userId;
    private String realName;
    private String email;
    private String phoneNumber;
    private String city;
    private StaticUserProfile.AgeGroup ageGroup;
    private Instant registrationDate;
    private String gender;
    private String sourceChannel;
    private Instant createdAt;
    private boolean isDeleted;




}
