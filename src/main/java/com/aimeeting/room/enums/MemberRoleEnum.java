package com.aimeeting.room.enums;

public enum MemberRoleEnum {

    PARTICIPANT(0, "参与者"),
    MODERATOR(1, "主持人");

    private final int code;
    private final String desc;

    MemberRoleEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public Integer getCode() { return code; }
    public String getDesc() { return desc; }
}
