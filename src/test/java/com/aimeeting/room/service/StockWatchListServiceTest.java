package com.aimeeting.room.service;

import com.aimeeting.room.dao.StockWatchItemMapper;
import com.aimeeting.room.entity.StockWatchItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockWatchListServiceTest {

    @Mock private StockWatchItemMapper itemMapper;
    @Mock private StockQuoteService stockQuoteService;

    private StockWatchListService service;

    @BeforeEach
    void setUp() {
        service = new StockWatchListService(itemMapper, stockQuoteService);
    }

    @Test
    void savePersistsRequestedGroupKey() {
        StockWatchItem saved = item("300750", "sz", "宁德时代", "holding");
        when(itemMapper.selectBySymbolMarket(eq("300750"), eq("sz")))
                .thenReturn(null, saved);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("symbol", "300750.sz");
        body.put("name", "宁德时代");
        body.put("groupKey", "holding");

        Map<String, Object> payload = service.save(body);

        ArgumentCaptor<StockWatchItem> captor = ArgumentCaptor.forClass(StockWatchItem.class);
        verify(itemMapper).insert(captor.capture());
        assertEquals("holding", captor.getValue().getGroupKey());
        assertEquals("holding", payload.get("groupKey"));
        assertEquals("持仓股", payload.get("groupName"));
    }

    @Test
    void saveWithoutGroupKeyKeepsExistingGroupKey() {
        StockWatchItem existing = item("688017", "sh", "绿的谐波", "candidate");
        existing.setId(12L);
        when(itemMapper.selectBySymbolMarket(eq("688017"), eq("sh")))
                .thenReturn(existing, existing);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("symbol", "688017.sh");
        body.put("name", "绿的谐波");

        Map<String, Object> payload = service.save(body);

        ArgumentCaptor<StockWatchItem> captor = ArgumentCaptor.forClass(StockWatchItem.class);
        verify(itemMapper).update(captor.capture());
        assertEquals("candidate", captor.getValue().getGroupKey());
        assertEquals("candidate", payload.get("groupKey"));
        assertEquals("备选股", payload.get("groupName"));
    }

    private StockWatchItem item(String symbol, String market, String name, String groupKey) {
        StockWatchItem item = new StockWatchItem();
        item.setSymbol(symbol);
        item.setMarket(market);
        item.setStockName(name);
        item.setImageUrl("");
        item.setEnabled(true);
        item.setSource("manual");
        item.setGroupKey(groupKey);
        return item;
    }
}
