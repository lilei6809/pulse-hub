package com.pulsehub.profileservice.controller.dto;

import lombok.Data;

@Data
public class IncPageViewsRequest {

    String userId;
    long pageViews;
}
