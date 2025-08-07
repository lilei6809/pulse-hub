package com.pulsehub.profileservice.domain;

public enum DeviceClass {
    MOBILE,
    DESKTOP,
    TABLET,
    SMART_TV,
    OTHER,
    UNKNOWN;

    public static DeviceClass from(String value) {
        try {
            return DeviceClass.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            return OTHER;
        }
    }
}
