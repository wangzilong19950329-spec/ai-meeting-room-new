package com.aimeeting.room.enums;

public enum MeetingStatusEnum {

    CREATED(0, "已创建"),
    IN_PROGRESS(1, "进行中"),
    CONCLUDED(2, "已达成结论"),
    VERIFIED_VALID(3, "结论已验证有效"),
    VERIFIED_INVALID(4, "结论已验证无效"),
    PAUSED(5, "已暂停"),
    REOPENED(6, "结论修订中"),
    CANCELLED(7, "已取消");

    private final int code;
    private final String desc;

    MeetingStatusEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public Integer getCode() { return code; }
    public String getDesc() { return desc; }

    public static MeetingStatusEnum of(int code) {
        for (MeetingStatusEnum e : values()) {
            if (e.code == code) return e;
        }
        return null;
    }
}
