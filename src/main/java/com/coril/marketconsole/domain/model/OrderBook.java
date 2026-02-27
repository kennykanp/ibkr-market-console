package com.coril.marketconsole.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class OrderBook {

    public static final int OP_INSERT = 0;
    public static final int OP_UPDATE = 1;
    public static final int OP_DELETE = 2;

    public static final int SIDE_ASK = 0;
    public static final int SIDE_BID = 1;

    private final String symbol;
    private final TreeMap<BigDecimal, DepthEntry> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<BigDecimal, DepthEntry> asks = new TreeMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public OrderBook(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    public void applyUpdate(int operation, int side, int position, BigDecimal price, long size, String marketMaker) {
        lock.writeLock().lock();
        try {
            var book = (side == SIDE_BID) ? bids : asks;

            switch (operation) {
                case OP_INSERT, OP_UPDATE -> {
                    var entry = new DepthEntry(position, price, size, marketMaker, Instant.now());
                    book.put(price, entry);
                }
                case OP_DELETE -> book.remove(price);
                default -> { /* ignore unknown operations */ }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<DepthEntry> getTopBids(int n) {
        lock.readLock().lock();
        try {
            return bids.values().stream().limit(n).toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<DepthEntry> getTopAsks(int n) {
        lock.readLock().lock();
        try {
            return asks.values().stream().limit(n).toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public long getMaxSize() {
        lock.readLock().lock();
        try {
            long maxBid = bids.values().stream().mapToLong(DepthEntry::size).max().orElse(1L);
            long maxAsk = asks.values().stream().mapToLong(DepthEntry::size).max().orElse(1L);
            return Math.max(maxBid, maxAsk);
        } finally {
            lock.readLock().unlock();
        }
    }
}
