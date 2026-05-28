package com.aimeeting.room.enums;

public enum WorkflowRunStatusEnum {
    PENDING("PENDING", "待开始"),
    ACTIVE("ACTIVE", "执行中"),
    WAITING_TRACKING("WAITING_TRACKING", "等待追踪"),
    CONCLUDED("CONCLUDED", "已收敛"),
    REOPENED("REOPENED", "已重开");

    private final String code;
    private final String desc;

    WorkflowRunStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static WorkflowRunStatusEnum of(String code) {
        if (code == null) return null;
        for (WorkflowRunStatusEnum value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return value;
            }
        }
        return null;
    }
}
