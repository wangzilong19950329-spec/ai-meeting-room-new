package com.aimeeting.room.controller;

import com.aimeeting.room.service.StockWatchN8nFacadeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/stock-watch/n8n")
public class StockWatchN8nController {

    private final StockWatchN8nFacadeService facadeService;
    private final boolean enabled;
    private final String expectedToken;

    public StockWatchN8nController(
            StockWatchN8nFacadeService facadeService,
            @Value("${STOCK_WATCH_N8N_ADAPTER_ENABLED:false}") boolean enabled,
            @Value("${STOCK_WATCH_N8N_API_TOKEN:}") String expectedToken) {
        this.facadeService = facadeService;
        this.enabled = enabled;
        this.expectedToken = expectedToken == null ? "" : expectedToken.trim();
    }

    @GetMapping("/quotes")
    public ResponseEntity<Map<String, Object>> quotes(
            @RequestParam("symbols") String symbols,
            @RequestHeader(value = "X-N8N-Token", required = false) String suppliedToken) {
        if (!enabled) {
            return response(HttpStatus.SERVICE_UNAVAILABLE, "JAVA_ADAPTER_DISABLED");
        }
        if (expectedToken.isEmpty() || !constantTimeEquals(expectedToken, suppliedToken)) {
            return response(HttpStatus.UNAUTHORIZED, "JAVA_ADAPTER_UNAUTHORIZED");
        }
        return ResponseEntity.ok(facadeService.fetchQuotes(symbols));
    }

    private ResponseEntity<Map<String, Object>> response(HttpStatus status, String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", StockWatchN8nFacadeService.SCHEMA_VERSION);
        body.put("error", code);
        body.put("items", java.util.Collections.emptyList());
        return ResponseEntity.status(status).body(body);
    }

    private boolean constantTimeEquals(String expected, String supplied) {
        if (supplied == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.trim().getBytes(StandardCharsets.UTF_8)
        );
    }
}
