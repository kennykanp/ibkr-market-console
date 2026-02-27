package com.coril.marketconsole.application;

import com.coril.marketconsole.domain.model.DepthEntry;
import com.coril.marketconsole.domain.model.OrderBook;
import com.coril.marketconsole.domain.model.Quote;
import com.coril.marketconsole.domain.port.DepthBookPort;
import com.coril.marketconsole.domain.port.MarketDataPort;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ConsoleRenderer implements MarketDataPort, DepthBookPort {

    private static final String CLEAR = "\033[2J\033[H";
    private static final String RESET = "\033[0m";
    private static final String GREEN = "\033[32m";
    private static final String RED = "\033[31m";
    private static final String CYAN = "\033[36m";
    private static final String YELLOW = "\033[33m";
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";

    private static final int MAX_BAR_WIDTH = 30;
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final List<String> symbols;
    private final int depthLevels;
    private final ConcurrentHashMap<String, Quote> quotes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OrderBook> books = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "console-renderer");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean dirty = false;

    public ConsoleRenderer(List<String> symbols, int depthLevels) {
        this.symbols = symbols;
        this.depthLevels = depthLevels;
        symbols.forEach(s -> {
            quotes.put(s, Quote.empty(s));
            books.put(s, new OrderBook(s));
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::render, 500, 250, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void onQuoteUpdate(Quote quote) {
        quotes.put(quote.symbol(), quote);
        dirty = true;
    }

    @Override
    public void onDepthUpdate(String symbol, OrderBook orderBook) {
        books.put(symbol, orderBook);
        dirty = true;
    }

    private void render() {
        if (!dirty) {
            return;
        }
        dirty = false;

        var sb = new StringBuilder(4096);
        sb.append(CLEAR);
        sb.append(BOLD).append(CYAN)
                .append("  IBKR Market Data Console")
                .append(RESET).append(DIM)
                .append("  [refresh 250ms]")
                .append(RESET).append('\n');
        sb.append(line(80)).append('\n');

        for (int i = 0; i < symbols.size(); i++) {
            var sym = symbols.get(i);
            var q = quotes.get(sym);
            var book = books.get(sym);

            renderQuote(sb, q);
            renderDepth(sb, book);

            if (i < symbols.size() - 1) {
                sb.append(line(80)).append('\n');
            }
        }

        sb.append(DIM).append("  Press Ctrl+C to exit").append(RESET).append('\n');
        System.out.print(sb);
    }

    private void renderQuote(StringBuilder sb, Quote q) {
        var chg = q.change();
        var chgPct = q.changePercent();
        var chgColor = chg.signum() >= 0 ? GREEN : RED;
        var chgSign = chg.signum() >= 0 ? "+" : "";

        sb.append('\n');
        sb.append("  ").append(BOLD).append(CYAN).append(q.symbol()).append(RESET);
        sb.append("  ").append(BOLD).append(formatPrice(q.last())).append(RESET);
        sb.append("  ").append(chgColor)
                .append(chgSign).append(formatPrice(chg))
                .append(" (").append(chgSign).append(chgPct.setScale(2, RoundingMode.HALF_UP)).append("%)")
                .append(RESET);
        sb.append('\n');

        sb.append(YELLOW).append("  ╔══════════════════════════════════════════════════════════════════════════╗").append(RESET).append('\n');

        sb.append(YELLOW).append("  ║").append(RESET);
        sb.append(GREEN).append(String.format("  Bid: %-12s  x %-8s", formatPrice(q.bid()), formatSize(q.bidSize()))).append(RESET);
        sb.append("  │  ");
        sb.append(RED).append(String.format("Ask: %-12s  x %-8s", formatPrice(q.ask()), formatSize(q.askSize()))).append(RESET);
        sb.append(YELLOW).append("  ║").append(RESET).append('\n');

        sb.append(YELLOW).append("  ║").append(RESET);
        sb.append(String.format("  Last Size: %-8s  Vol: %-10s  Spread: %-8s",
                formatSize(q.lastSize()),
                formatVolume(q.volume()),
                formatPrice(q.spread())));
        sb.append(YELLOW).append("          ║").append(RESET).append('\n');

        if (q.lastTimestamp() != null && q.lastTimestamp().getEpochSecond() > 0) {
            sb.append(YELLOW).append("  ║").append(RESET);
            sb.append(DIM).append("  Last update: ").append(TIME_FMT.format(q.lastTimestamp())).append(RESET);
            sb.append(YELLOW).append(String.format("%52s", "║")).append(RESET).append('\n');
        }

        sb.append(YELLOW).append("  ╚══════════════════════════════════════════════════════════════════════════╝").append(RESET).append('\n');
    }

    private void renderDepth(StringBuilder sb, OrderBook book) {
        var topBids = book.getTopBids(depthLevels);
        var topAsks = book.getTopAsks(depthLevels);

        if (topBids.isEmpty() && topAsks.isEmpty()) {
            sb.append(DIM).append("  Waiting for depth data...").append(RESET).append('\n');
            return;
        }

        long maxSize = book.getMaxSize();
        int rows = Math.max(topBids.size(), topAsks.size());

        sb.append('\n');
        sb.append(YELLOW).append(String.format("  %-34s │ %-34s", "  BIDS (Buy)", "  ASKS (Sell)")).append(RESET).append('\n');
        sb.append(YELLOW).append("  ").append("─".repeat(35)).append("┼").append("─".repeat(35)).append(RESET).append('\n');

        for (int i = 0; i < rows; i++) {
            sb.append("  ");

            if (i < topBids.size()) {
                renderBidRow(sb, topBids.get(i), maxSize);
            } else {
                sb.append(String.format("%-34s", ""));
            }

            sb.append(DIM).append(" │ ").append(RESET);

            if (i < topAsks.size()) {
                renderAskRow(sb, topAsks.get(i), maxSize);
            } else {
                sb.append(String.format("%-34s", ""));
            }

            sb.append('\n');
        }
        sb.append('\n');
    }

    private void renderBidRow(StringBuilder sb, DepthEntry entry, long maxSize) {
        int barLen = (maxSize > 0) ? (int) (entry.size() * MAX_BAR_WIDTH / maxSize) : 0;
        barLen = Math.max(barLen, 1);
        var bar = "█".repeat(barLen);

        sb.append(GREEN);
        sb.append(String.format("%10s %7s ", formatPrice(entry.price()), formatSize(entry.size())));
        sb.append(bar);
        sb.append(String.format("%" + (MAX_BAR_WIDTH - barLen + 1) + "s", ""));
        // trim to fixed width
        sb.append(RESET);
    }

    private void renderAskRow(StringBuilder sb, DepthEntry entry, long maxSize) {
        int barLen = (maxSize > 0) ? (int) (entry.size() * MAX_BAR_WIDTH / maxSize) : 0;
        barLen = Math.max(barLen, 1);
        var bar = "█".repeat(barLen);

        sb.append(RED);
        sb.append(bar);
        sb.append(String.format("%" + (MAX_BAR_WIDTH - barLen + 1) + "s", ""));
        sb.append(String.format("%-7s %10s", formatSize(entry.size()), formatPrice(entry.price())));
        sb.append(RESET);
    }

    private static String formatPrice(BigDecimal price) {
        if (price == null || price.signum() == 0) {
            return "—";
        }
        return NumberFormat.getNumberInstance(Locale.US).format(price.setScale(2, RoundingMode.HALF_UP));
    }

    private static String formatSize(long size) {
        if (size <= 0) {
            return "—";
        }
        return NumberFormat.getIntegerInstance(Locale.US).format(size);
    }

    private static String formatVolume(long volume) {
        if (volume <= 0) {
            return "—";
        }
        if (volume >= 1_000_000) {
            return String.format("%.1fM", volume / 1_000_000.0);
        }
        if (volume >= 1_000) {
            return String.format("%.1fK", volume / 1_000.0);
        }
        return NumberFormat.getIntegerInstance(Locale.US).format(volume);
    }

    private static String line(int width) {
        return DIM + "  " + "─".repeat(width) + RESET;
    }
}
