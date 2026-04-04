package com.aimeeting.room.common;

import lombok.Data;

@Data
public class ApiResponse<T> {

    private int code;
    private String msg;
    private T data;

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = 200;
        r.msg = "success";
        r.data = data;
        return r;
    }

    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> fail(String msg) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = 500;
        r.msg = msg;
        return r;
    }

    public static <T> ApiResponse<T> fail(int code, String msg) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = code;
        r.msg = msg;
        return r;
    }
}
