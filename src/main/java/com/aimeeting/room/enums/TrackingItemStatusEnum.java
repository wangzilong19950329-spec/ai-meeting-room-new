package com.aimeeting.room.enums;

public enum TrackingItemStatusEnum {
    OPEN("OPEN", "待处理"),
    IN_PROGRESS("IN_PROGRESS", "处理中"),
    BLOCKED("BLOCKED", "已阻塞"),
    READY_FOR_REVIEW("READY_FOR_REVIEW", "待复核"),
    VERIFIED("VERIFIED", "已验证"),
    REJECTED("REJECTED", "已驳回"),
    CLOSED("CLOSED", "已关闭");

    private final String code;
    private final String desc;

    TrackingItemStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static TrackingItemStatusEnum of(String code) {
        if (code == null) return null;
        for (TrackingItemStatusEnum value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return value;
            }
        }
        return null;
    }
}
