# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Real-time Level 1 quotes and Level 2 market depth console for Interactive Brokers (IB Gateway/TWS). Pure Java 21 application with no framework — just the TWS API over a direct TCP socket.

## Build & Run

```bash
# Build fat JAR (includes TWS API)
mvn clean package

# Run (--symbols is required)
java -jar target/ibkr-market-console-1.0.0-SNAPSHOT.jar --symbols AAPL,MSFT

# Full options
java -jar target/ibkr-market-console-1.0.0-SNAPSHOT.jar \
  --host 127.0.0.1 --port 4002 --symbols AAPL,MSFT,GOOGL --depth-levels 10
```

No test suite exists yet. No Spring, no DI framework — dependency wiring is manual in `MarketConsoleApplication.main()`.

## TWS API Dependency

`tws-api:10.37` is **not on Maven Central**. It must be installed to the local Maven repo manually from IBKR's download (see README.md for install commands).

## Architecture — Hexagonal (Ports & Adapters)

```
CLI args
  └─► MarketConsoleApplication          (composition root, manual wiring)
        ├─► ConsoleRenderer             (application layer — implements both ports)
        ├─► IbkrMarketDataHandler       (infra — translates L1 ticks → Quote)
        ├─► IbkrDepthBookHandler        (infra — translates L2 depth → OrderBook)
        └─► IbkrConnectionManager       (infra — EWrapper impl, TCP lifecycle, auto-reconnect)
```

### Domain Layer (`domain/`)
- Zero external dependencies, pure Java
- `Quote` — immutable record, `BigDecimal` for all prices, fluent `withXxx()` builders
- `OrderBook` — mutable, `TreeMap`-backed, protected by `ReentrantReadWriteLock`
- `DepthEntry` — immutable record for a single depth level

### Ports (`domain/port/`)
- `MarketDataPort` — `@FunctionalInterface`, receives `Quote` updates (L1)
- `DepthBookPort` — `@FunctionalInterface`, receives `OrderBook` updates (L2)

### Application Layer (`application/`)
- `ConsoleRenderer` implements both ports; ANSI terminal rendering at 250ms refresh via `ScheduledExecutorService`

### Infrastructure Layer (`infrastructure/ibkr/`)
- `IbkrConnectionManager` — full `EWrapper` implementation, manages `EClientSocket`/`EReader`, exponential backoff reconnect (up to 30s)
- `IbkrMarketDataHandler` — translates TWS tick types into `Quote` field updates
- `IbkrDepthBookHandler` — translates TWS depth events into `OrderBook.applyUpdate()` calls

## Thread Model

| Thread               | Role                                                        |
|----------------------|-------------------------------------------------------------|
| `main`               | Blocked on `CountDownLatch`, kept alive for shutdown hook    |
| `tws-msg-processor`  | `EReader` loop — processes TWS socket messages               |
| `console-renderer`   | Scheduled at 250ms — redraws only when `volatile dirty` flag |

## Key Design Decisions

- **No DI framework** — composition root in `main()`, all wiring explicit
- **`BigDecimal` for prices** — financial precision, no floating-point
- **`ConcurrentHashMap`** for L1 quotes and L2 order books (keyed by symbol)
- **`ReentrantReadWriteLock`** inside `OrderBook` for concurrent depth mutations + reads
- **`volatile boolean dirty`** flag avoids unnecessary console redraws
- **`java.util.logging`** — no external logging dependency
