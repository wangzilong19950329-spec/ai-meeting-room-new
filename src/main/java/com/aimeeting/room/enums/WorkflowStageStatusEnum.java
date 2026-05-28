package com.aimeeting.room.enums;

public enum WorkflowStageStatusEnum {
    PENDING("PENDING", "待执行"),
    ACTIVE("ACTIVE", "执行中"),
    COMPLETED("COMPLETED", "已完成"),
    BLOCKED("BLOCKED", "已阻塞"),
    REJECTED("REJECTED", "已打回");

    private final String code;
    private final String desc;

    WorkflowStageStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static WorkflowStageStatusEnum of(String code) {
        if (code == null) return null;
        for (WorkflowStageStatusEnum value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return value;
            }
        }
        return null;
    }
}
