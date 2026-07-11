package com.aimeeting.room.service.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchServiceSearchResultTest {

    @Test
    void traceMetadataIsFilledAndCopyable() {
        WebSearchService.SearchResult source = new WebSearchService.SearchResult()
                .withTrace(" eastmoney ", " STRUCTURED_DATA ", " EASTMONEY:002979.SZ:finance ", "{\"roe\":12.3}");

        assertEquals("eastmoney", source.provider);
        assertEquals("STRUCTURED_DATA", source.sourceType);
        assertEquals("EASTMONEY:002979.SZ:finance", source.query);
        assertNotNull(source.fetchedAt);
        assertTrue(source.rawHash.startsWith("sha256:"));

        WebSearchService.SearchResult copy = new WebSearchService.SearchResult();
        copy.copyTraceFrom(source);

        assertEquals(source.provider, copy.provider);
        assertEquals(source.sourceType, copy.sourceType);
        assertEquals(source.query, copy.query);
        assertEquals(source.fetchedAt, copy.fetchedAt);
        assertEquals(source.rawHash, copy.rawHash);
    }
}
