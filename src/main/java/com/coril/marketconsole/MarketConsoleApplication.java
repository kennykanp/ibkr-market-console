package com.coril.marketconsole;

import com.coril.marketconsole.application.ConsoleRenderer;
import com.coril.marketconsole.infrastructure.ibkr.IbkrConnectionManager;
import com.coril.marketconsole.infrastructure.ibkr.IbkrDepthBookHandler;
import com.coril.marketconsole.infrastructure.ibkr.IbkrMarketDataHandler;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

public class MarketConsoleApplication {

    private static final Logger LOG = Logger.getLogger(MarketConsoleApplication.class.getName());

    public static void main(String[] args) {
        var host = getArg(args, "--host", "127.0.0.1");
        var port = Integer.parseInt(getArg(args, "--port", "4002"));
        var symbolsCsv = getArg(args, "--symbols", null);
        var depthLevels = Integer.parseInt(getArg(args, "--depth-levels", "20"));

        if (symbolsCsv == null || symbolsCsv.isBlank()) {
            System.err.println("Usage: java -jar ibkr-market-console.jar --symbols AAPL,MSFT [--host 127.0.0.1] [--port 4002] [--depth-levels 20]");
            System.exit(1);
        }

        List<String> symbols = Arrays.stream(symbolsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toUpperCase)
                .toList();

        LOG.info("Starting IBKR Market Console for symbols: %s".formatted(symbols));

        // Hexagonal wiring — composition root
        var renderer = new ConsoleRenderer(symbols, depthLevels);
        var marketDataHandler = new IbkrMarketDataHandler(renderer);
        var depthBookHandler = new IbkrDepthBookHandler(renderer);
        var connectionManager = new IbkrConnectionManager(marketDataHandler, depthBookHandler, symbols, depthLevels);

        var shutdownLatch = new CountDownLatch(1);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down...");
            connectionManager.disconnect();
            renderer.stop();
            shutdownLatch.countDown();
        }, "shutdown-hook"));

        renderer.start();
        connectionManager.connect(host, port);

        try {
            shutdownLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String getArg(String[] args, String name, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return defaultValue;
    }
}
