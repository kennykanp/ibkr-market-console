# IBKR Market Data Console

Real-time Level 1 quotes and Level 2 market depth console for Interactive Brokers (IB Gateway/TWS).

```
┌─────────────────────────────────────────────────┐
│  CLI (args)                                     │
│  ┌───────────────────────────────────────────┐  │
│  │  Application: ConsoleRenderer             │  │
│  │  (MarketDataPort + DepthBookPort)         │  │
│  └──────────────┬────────────────────────────┘  │
│         Domain  │  (Quote, OrderBook, Ports)     │
│  ┌──────────────┴────────────────────────────┐  │
│  │  Infrastructure: IBKR Adapters            │  │
│  │  ConnectionManager → TWS API (TCP)        │  │
│  └───────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘
```

## Prerequisites

- Java 21+
- Maven 3.9+
- Interactive Brokers Gateway (IB Gateway) or Trader Workstation (TWS)
- Market data subscription for NYSE/NASDAQ stocks

## Installing TWS API JAR

The TWS API JAR is not on Maven Central. Install it to your local Maven repository:

### Option A: From the official IBKR API download

1. Download the TWS API from [IBKR API Downloads](https://interactivebrokers.github.io/)
2. Extract the archive, locate `TwsApi.jar` under `source/JavaClient/`
3. Install to local Maven repo:

```bash
mvn install:install-file \
  -Dfile=TwsApi.jar \
  -DgroupId=com.interactivebrokers \
  -DartifactId=tws-api \
  -Dversion=10.19 \
  -Dpackaging=jar
```

### Option B: Build from source

```bash
cd IBJts/source/JavaClient
ant
mvn install:install-file \
  -Dfile=TwsApi.jar \
  -DgroupId=com.interactivebrokers \
  -DartifactId=tws-api \
  -Dversion=10.19 \
  -Dpackaging=jar
```

## IB Gateway / TWS Configuration

1. Open IB Gateway (or TWS) and log in with your **paper trading** account
2. Go to **Configuration > API > Settings**:
   - Enable **Socket Clients**
   - Set **Socket port** to `4002` (Gateway default) or `7497` (TWS default)
   - Uncheck **Read-Only API** if needed
   - Add `127.0.0.1` to **Trusted IPs**
3. Ensure **Market Data** subscriptions are active for the symbols you want to monitor

## Docker Quick Start (alternative)

Instead of installing IB Gateway locally, run it in Docker:

```bash
cp .env.example .env
# Edit .env with your IBKR credentials
docker compose up -d
```

Wait for the health check to pass (`docker compose ps`), then run the console:

```bash
java -jar target/ibkr-market-console-1.0.0-SNAPSHOT.jar --symbols AAPL,MSFT
```

Port `5900` exposes a VNC server for debugging the gateway UI.

## Build

```bash
cd dma-market-ws-ibkr
mvn clean package
```

## Run

```bash
# Basic usage
java -jar target/ibkr-market-console-1.0.0-SNAPSHOT.jar --symbols AAPL,MSFT

# Full options
java -jar target/ibkr-market-console-1.0.0-SNAPSHOT.jar \
  --host 127.0.0.1 \
  --port 4002 \
  --symbols AAPL,MSFT,GOOGL,AMZN \
  --depth-levels 10
```

### CLI Arguments

| Argument         | Default     | Description                     |
|------------------|-------------|---------------------------------|
| `--host`         | `127.0.0.1` | IB Gateway/TWS host             |
| `--port`         | `4002`      | IB Gateway/TWS socket port      |
| `--symbols`      | *(required)* | Comma-separated stock symbols   |
| `--depth-levels` | `20`        | Number of depth levels to show  |

## Troubleshooting

| Error Code | Message                          | Solution                                           |
|------------|----------------------------------|----------------------------------------------------|
| 502        | Couldn't connect to TWS          | Ensure IB Gateway/TWS is running and API is enabled |
| 504        | Not connected                    | Check host/port configuration                      |
| 354        | Not subscribed to market data    | Subscribe to market data in Account Management      |
| 10167      | Delayed market data              | Normal for accounts without real-time subscriptions |
| 2104/2106  | Market data farm connection      | Informational, can be ignored                      |

## Architecture

- **Domain Layer**: `Quote`, `DepthEntry`, `OrderBook` — zero external dependencies, `BigDecimal` for all prices
- **Ports**: `MarketDataPort`, `DepthBookPort` — functional interfaces for loose coupling
- **Application Layer**: `ConsoleRenderer` — ANSI terminal rendering at 250ms refresh
- **Infrastructure**: `IbkrConnectionManager` (EWrapper) + handlers — TWS API adapter with auto-reconnect

## Thread Model

1. **main** — blocked on `CountDownLatch`, kept alive for shutdown hook
2. **tws-msg-processor** — processes EReader messages, triggers EWrapper callbacks
3. **console-renderer** — `ScheduledExecutorService`, redraws every 250ms when dirty
