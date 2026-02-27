package com.coril.marketconsole.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

public record Quote(
        String symbol,
        BigDecimal bid,
        BigDecimal ask,
        BigDecimal last,
        BigDecimal close,
        long bidSize,
        long askSize,
        long lastSize,
        long volume,
        Instant lastTimestamp,
        Instant updatedAt
) {

    public static Quote empty(String symbol) {
        return new Quote(
                symbol,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                0L, 0L, 0L, 0L,
                Instant.EPOCH, Instant.now()
        );
    }

    public BigDecimal change() {
        if (last.signum() == 0 || close.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return last.subtract(close);
    }

    public BigDecimal changePercent() {
        if (last.signum() == 0 || close.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return last.subtract(close)
                .divide(close, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    public BigDecimal spread() {
        if (ask.signum() == 0 || bid.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return ask.subtract(bid);
    }

    public Quote withBid(BigDecimal val) {
        return new Quote(symbol, val, ask, last, close, bidSize, askSize, lastSize, volume, lastTimestamp, Instant.now());
    }

    public Quote withAsk(BigDecimal val) {
        return new Quote(symbol, bid, val, last, close, bidSize, askSize, lastSize, volume, lastTimestamp, Instant.now());
    }

    public Quote withLast(BigDecimal val) {
        return new Quote(symbol, bid, ask, val, close, bidSize, askSize, lastSize, volume, lastTimestamp, Instant.now());
    }

    public Quote withClose(BigDecimal val) {
        return new Quote(symbol, bid, ask, last, val, bidSize, askSize, lastSize, volume, lastTimestamp, Instant.now());
    }

    public Quote withBidSize(long val) {
        return new Quote(symbol, bid, ask, last, close, val, askSize, lastSize, volume, lastTimestamp, Instant.now());
    }

    public Quote withAskSize(long val) {
        return new Quote(symbol, bid, ask, last, close, bidSize, val, lastSize, volume, lastTimestamp, Instant.now());
    }

    public Quote withLastSize(long val) {
        return new Quote(symbol, bid, ask, last, close, bidSize, askSize, val, volume, lastTimestamp, Instant.now());
    }

    public Quote withVolume(long val) {
        return new Quote(symbol, bid, ask, last, close, bidSize, askSize, lastSize, val, lastTimestamp, Instant.now());
    }

    public Quote withLastTimestamp(Instant val) {
        return new Quote(symbol, bid, ask, last, close, bidSize, askSize, lastSize, volume, val, Instant.now());
    }
}
