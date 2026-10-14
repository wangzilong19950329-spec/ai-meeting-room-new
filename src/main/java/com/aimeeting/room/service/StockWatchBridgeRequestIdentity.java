package com.aimeeting.room.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

public final class StockWatchBridgeRequestIdentity {

    private StockWatchBridgeRequestIdentity() {
    }

    public static String digest(String query,
                                String capability,
                                String sessionKey,
                                String mode,
                                boolean expectedJson,
                                boolean forceNewSession,
                                boolean isolatedSession,
                                boolean closeTargetAfter,
                                String accountKey) {
        return sha256(canonicalPayload(query, capability, sessionKey, mode, expectedJson,
                forceNewSession, isolatedSession, closeTargetAfter, accountKey));
    }

    public static String digestWithoutSearch(String query,
                                             String capability,
                                             String sessionKey,
                                             String mode,
                                             boolean expectedJson,
                                             boolean forceNewSession,
                                             boolean isolatedSession,
                                             boolean closeTargetAfter,
                                             String accountKey) {
        return sha256(canonicalPayload(query, capability, sessionKey, mode, expectedJson,
                forceNewSession, isolatedSession, closeTargetAfter, accountKey)
                + "enableSearch:5:false\n");
    }

    static String canonicalPayload(String query,
                                   String capability,
                                   String sessionKey,
                                   String mode,
                                   boolean expectedJson,
                                   boolean forceNewSession,
                                   boolean isolatedSession,
                                   boolean closeTargetAfter,
                                   String accountKey) {
        String normalizedSessionKey = normalizeSessionKey(sessionKey);
        boolean effectiveCloseTargetAfter = normalizedSessionKey.isEmpty()
                && isolatedSession && closeTargetAfter;
        StringBuilder payload = new StringBuilder("deepseek-operation-v1\n");
        append(payload, "query", trimEcmaWhitespace(text(query)));
        append(payload, "capability", trimEcmaWhitespace(text(capability)).toLowerCase(Locale.ROOT));
        append(payload, "sessionKey", normalizedSessionKey);
        append(payload, "mode", trimEcmaWhitespace(text(mode)).toLowerCase(Locale.ROOT));
        append(payload, "expectedJson", Boolean.toString(expectedJson));
        append(payload, "forceNewSession", Boolean.toString(forceNewSession));
        append(payload, "isolatedSession", Boolean.toString(isolatedSession));
        append(payload, "closeTargetAfter", Boolean.toString(effectiveCloseTargetAfter));
        append(payload, "accountKey", trimEcmaWhitespace(text(accountKey)));
        return payload.toString();
    }

    private static void append(StringBuilder payload, String name, String value) {
        payload.append(name).append(':').append(value.length()).append(':').append(value).append('\n');
    }

    private static String normalizeSessionKey(String raw) {
        String value = trimEcmaWhitespace(text(raw));
        if (value.isEmpty()) {
            return "";
        }
        if (value.length() <= 120 && value.matches("[a-zA-Z0-9_.:-]+")) {
            return value;
        }
        return "session-" + sha256(value);
    }

    private static String trimEcmaWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isEcmaWhitespace(codePoint)) break;
            start += Character.charCount(codePoint);
        }
        while (end > start) {
            int codePoint = value.codePointBefore(end);
            if (!isEcmaWhitespace(codePoint)) break;
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private static boolean isEcmaWhitespace(int codePoint) {
        return codePoint == '\t'
                || codePoint == '\n'
                || codePoint == 0x000B
                || codePoint == '\f'
                || codePoint == '\r'
                || codePoint == 0x00A0
                || codePoint == 0x2028
                || codePoint == 0x2029
                || codePoint == 0xFEFF
                || Character.getType(codePoint) == Character.SPACE_SEPARATOR;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
