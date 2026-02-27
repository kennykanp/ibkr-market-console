package com.coril.marketconsole.domain.port;

import com.coril.marketconsole.domain.model.OrderBook;

@FunctionalInterface
public interface DepthBookPort {

    void onDepthUpdate(String symbol, OrderBook orderBook);
}
