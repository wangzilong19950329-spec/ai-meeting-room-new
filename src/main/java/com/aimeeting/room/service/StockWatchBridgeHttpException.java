package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

public final class StockWatchBridgeHttpException extends IllegalStateException {

    private final int statusCode;
    private final String errorCode;
    private final boolean providerOutcomeUnknown;
    private final Boolean retrySafe;

    private StockWatchBridgeHttpException(int statusCode,
                                          String errorCode,
                                          boolean providerOutcomeUnknown,
                                          Boolean retrySafe,
                                          String message) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
        this.providerOutcomeUnknown = providerOutcomeUnknown;
        this.retrySafe = retrySafe;
    }

    public static StockWatchBridgeHttpException fromResponse(String bridgeName,
                                                             int statusCode,
                                                             String responseBody) {
        JSONObject payload = null;
        try {
            payload = JSON.parseObject(responseBody);
        } catch (Exception ignored) {
            // The HTTP status still carries the failure when an upstream proxy returns non-JSON.
        }
        String errorCode = text(payload == null ? null : payload.get("error"), "bridge_error");
        boolean providerOutcomeUnknown = payload != null
                && Boolean.TRUE.equals(payload.getBoolean("providerOutcomeUnknown"));
        Boolean retrySafe = payload == null || !payload.containsKey("retrySafe")
                ? null
                : payload.getBoolean("retrySafe");
        String detail = text(payload == null ? null : payload.get("message"), truncate(responseBody, 300));
        String message = bridgeName + " HTTP " + statusCode
                + " error=" + errorCode
                + " providerOutcomeUnknown=" + providerOutcomeUnknown
                + " retrySafe=" + retrySafe
                + " message=" + truncate(detail, 300);
        return new StockWatchBridgeHttpException(
                statusCode, errorCode, providerOutcomeUnknown, retrySafe, message);
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public boolean isProviderOutcomeUnknown() {
        return providerOutcomeUnknown;
    }

    public Boolean getRetrySafe() {
        return retrySafe;
    }

    private static String text(Object value, String fallback) {
        if (value == null) {
            return fallback == null ? "" : fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? (fallback == null ? "" : fallback) : text;
    }

    private static String truncate(String value, int maxLength) {
        String text = value == null ? "" : value;
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}
