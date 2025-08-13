package com.pulsehub.profileservice.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.pulsehub.profileservice.domain.DeviceClass;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class CreateDynamicUserProfileRequest {

    private String userId;
    private Long pageViewCount;

    //TODO: 这个地方需要使用 DeviceClassifier 对 device 进行设备分类啊
    // DeviceClassifier怎么使用
//    @JsonProperty("deviceClassification")
    private String device;

//    private DeviceClass deviceClass = DeviceClass.UNKNOWN;

//    @JsonSetter("deviceClassification")
//    public void setDevice(String device) {
//        this.device = device;
//        this.deviceClass = DeviceClass.from(device);
//    }
}

