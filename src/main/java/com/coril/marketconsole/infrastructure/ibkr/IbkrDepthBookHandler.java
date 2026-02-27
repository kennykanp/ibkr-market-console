package com.coril.marketconsole.infrastructure.ibkr;

import com.coril.marketconsole.domain.model.OrderBook;
import com.coril.marketconsole.domain.port.DepthBookPort;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class IbkrDepthBookHandler {

    private final ConcurrentHashMap<Integer, OrderBook> booksByReqId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> symbolsByReqId = new ConcurrentHashMap<>();
    private final DepthBookPort depthBookPort;

    public IbkrDepthBookHandler(DepthBookPort depthBookPort) {
        this.depthBookPort = depthBookPort;
    }

    public void registerSymbol(int reqId, String symbol) {
        var book = new OrderBook(symbol);
        booksByReqId.put(reqId, book);
        symbolsByReqId.put(reqId, symbol);
    }

    public void handleUpdateMktDepth(int reqId, int position, int operation, int side, double price, long size) {
        applyAndNotify(reqId, operation, side, position, BigDecimal.valueOf(price), size, "");
    }

    public void handleUpdateMktDepthL2(int reqId, int position, String marketMaker, int operation,
                                       int side, double price, long size) {
        applyAndNotify(reqId, operation, side, position, BigDecimal.valueOf(price), size, marketMaker);
    }

    public Map<Integer, OrderBook> getBooks() {
        return Map.copyOf(booksByReqId);
    }

    private void applyAndNotify(int reqId, int operation, int side, int position,
                                BigDecimal price, long size, String marketMaker) {
        var book = booksByReqId.get(reqId);
        if (book == null) {
            return;
        }

        book.applyUpdate(operation, side, position, price, size, marketMaker);

        var symbol = symbolsByReqId.get(reqId);
        if (symbol != null) {
            depthBookPort.onDepthUpdate(symbol, book);
        }
    }
}
