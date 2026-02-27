package com.coril.marketconsole.domain.port;

import com.coril.marketconsole.domain.model.Quote;

@FunctionalInterface
public interface MarketDataPort {

    void onQuoteUpdate(Quote quote);
}
