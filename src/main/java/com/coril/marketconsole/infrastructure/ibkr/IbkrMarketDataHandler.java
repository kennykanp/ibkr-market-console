package com.coril.marketconsole.infrastructure.ibkr;

import com.coril.marketconsole.domain.model.Quote;
import com.coril.marketconsole.domain.port.MarketDataPort;
import com.ib.client.Decimal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class IbkrMarketDataHandler {

    // TWS tick type IDs
    static final int TICK_BID_SIZE = 0;
    static final int TICK_BID = 1;
    static final int TICK_ASK = 2;
    static final int TICK_ASK_SIZE = 3;
    static final int TICK_LAST = 4;
    static final int TICK_LAST_SIZE = 5;
    static final int TICK_VOLUME = 8;
    static final int TICK_CLOSE = 9;
    static final int TICK_LAST_TIMESTAMP = 45;

    private final ConcurrentHashMap<Integer, Quote> quotesByReqId = new ConcurrentHashMap<>();
    private final MarketDataPort marketDataPort;

    public IbkrMarketDataHandler(MarketDataPort marketDataPort) {
        this.marketDataPort = marketDataPort;
    }

    public void registerSymbol(int reqId, String symbol) {
        quotesByReqId.put(reqId, Quote.empty(symbol));
    }

    public void handleTickPrice(int reqId, int tickType, double price) {
        var bdPrice = BigDecimal.valueOf(price);

        quotesByReqId.computeIfPresent(reqId, (id, prev) -> switch (tickType) {
            case TICK_BID -> prev.withBid(bdPrice);
            case TICK_ASK -> prev.withAsk(bdPrice);
            case TICK_LAST -> prev.withLast(bdPrice);
            case TICK_CLOSE -> prev.withClose(bdPrice);
            default -> prev;
        });

        notifyIfPresent(reqId);
    }

    public void handleTickSize(int reqId, int tickType, Decimal size) {
        if (!size.isValid()) {
            return;
        }
        long longSize = size.longValue();

        quotesByReqId.computeIfPresent(reqId, (id, prev) -> switch (tickType) {
            case TICK_BID_SIZE -> prev.withBidSize(longSize);
            case TICK_ASK_SIZE -> prev.withAskSize(longSize);
            case TICK_LAST_SIZE -> prev.withLastSize(longSize);
            case TICK_VOLUME -> prev.withVolume(longSize);
            default -> prev;
        });

        notifyIfPresent(reqId);
    }

    public void handleTickString(int reqId, int tickType, String value) {
        if (tickType != TICK_LAST_TIMESTAMP) {
            return;
        }

        try {
            long epochSeconds = Long.parseLong(value);
            var timestamp = Instant.ofEpochSecond(epochSeconds);

            quotesByReqId.computeIfPresent(reqId, (id, prev) -> prev.withLastTimestamp(timestamp));
            notifyIfPresent(reqId);
        } catch (NumberFormatException ignored) {
            // TWS may send non-numeric strings for some tick types
        }
    }

    public Map<Integer, Quote> getQuotes() {
        return Map.copyOf(quotesByReqId);
    }

    private void notifyIfPresent(int reqId) {
        var quote = quotesByReqId.get(reqId);
        if (quote != null) {
            marketDataPort.onQuoteUpdate(quote);
        }
    }
}
