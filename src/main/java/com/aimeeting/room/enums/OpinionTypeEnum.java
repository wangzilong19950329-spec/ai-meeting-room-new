package com.aimeeting.room.enums;

public enum OpinionTypeEnum {

    STATEMENT(0, "陈述"),
    REBUTTAL(1, "反驳"),
    CONCESSION(2, "让步");

    private final int code;
    private final String desc;

    OpinionTypeEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public Integer getCode() { return code; }
    public String getDesc() { return desc; }

    public static OpinionTypeEnum of(int code) {
        for (OpinionTypeEnum e : values()) {
            if (e.code == code) return e;
        }
        return null;
    }
}
