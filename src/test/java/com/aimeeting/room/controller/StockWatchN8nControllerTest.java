package com.aimeeting.room.controller;

import com.aimeeting.room.service.StockWatchN8nFacadeService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockWatchN8nControllerTest {

    @Test
    void rejectsRequestsWhileAdapterIsDisabled() {
        StockWatchN8nController controller = new StockWatchN8nController(
                mock(StockWatchN8nFacadeService.class), false, "secret");

        ResponseEntity<Map<String, Object>> response = controller.quotes("300750.sz", "secret");

        assertEquals(503, response.getStatusCodeValue());
        assertEquals("JAVA_ADAPTER_DISABLED", response.getBody().get("error"));
    }

    @Test
    void rejectsMissingOrWrongDedicatedToken() {
        StockWatchN8nController controller = new StockWatchN8nController(
                mock(StockWatchN8nFacadeService.class), true, "secret");

        assertEquals(401, controller.quotes("300750.sz", null).getStatusCodeValue());
        assertEquals(401, controller.quotes("300750.sz", "wrong").getStatusCodeValue());
    }

    @Test
    void delegatesAuthorizedReadOnlyQuoteRequest() {
        StockWatchN8nFacadeService facade = mock(StockWatchN8nFacadeService.class);
        when(facade.fetchQuotes("300750.sz")).thenReturn(Map.of(
                "schemaVersion", "stock-watch.n8n.quote.v1",
                "items", java.util.Collections.emptyList()
        ));
        StockWatchN8nController controller = new StockWatchN8nController(facade, true, "secret");

        ResponseEntity<Map<String, Object>> response = controller.quotes("300750.sz", "secret");

        assertEquals(200, response.getStatusCodeValue());
        assertEquals("stock-watch.n8n.quote.v1", response.getBody().get("schemaVersion"));
        verify(facade).fetchQuotes("300750.sz");
    }
}
