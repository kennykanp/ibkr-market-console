package com.coril.marketconsole.infrastructure.ibkr;

import com.ib.client.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public class IbkrConnectionManager implements EWrapper {

    private static final Logger LOG = Logger.getLogger(IbkrConnectionManager.class.getName());

    private static final int L1_BASE_ID = 1000;
    private static final int L2_BASE_ID = 6000;
    private static final long MAX_RECONNECT_DELAY_MS = 30_000;
    private static final Set<Integer> WARN_ONLY_ERRORS = Set.of(354, 10167, 10168, 2104, 2106, 2158);

    private final IbkrMarketDataHandler marketDataHandler;
    private final IbkrDepthBookHandler depthBookHandler;
    private final List<String> symbols;
    private final int depthLevels;

    private EClientSocket clientSocket;
    private EJavaSignal signal;
    private CountDownLatch connectedLatch;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempt = new AtomicInteger(0);

    private String host;
    private int port;
    private int clientId;

    public IbkrConnectionManager(IbkrMarketDataHandler marketDataHandler,
                                 IbkrDepthBookHandler depthBookHandler,
                                 List<String> symbols,
                                 int depthLevels) {
        this.marketDataHandler = marketDataHandler;
        this.depthBookHandler = depthBookHandler;
        this.symbols = symbols;
        this.depthLevels = depthLevels;
    }

    public void connect(String host, int port) {
        this.host = host;
        this.port = port;
        this.clientId = (int) (System.currentTimeMillis() % 10000);
        doConnect();
    }

    private void doConnect() {
        signal = new EJavaSignal();
        clientSocket = new EClientSocket(this, signal);
        connectedLatch = new CountDownLatch(1);

        LOG.info("Connecting to %s:%d with clientId=%d...".formatted(host, port, clientId));
        clientSocket.eConnect(host, port, clientId);

        var reader = new EReader(clientSocket, signal);
        reader.start();

        var msgThread = new Thread(() -> {
            while (clientSocket.isConnected()) {
                signal.waitForSignal();
                try {
                    reader.processMsgs();
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Error processing TWS message", e);
                }
            }
            LOG.info("Message processing thread stopped.");
            if (connected.getAndSet(false)) {
                scheduleReconnect();
            }
        }, "tws-msg-processor");
        msgThread.setDaemon(true);
        msgThread.start();

        try {
            if (!connectedLatch.await(10, TimeUnit.SECONDS)) {
                LOG.severe("Timeout waiting for connection. Is IB Gateway/TWS running on %s:%d?".formatted(host, port));
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        connected.set(true);
        reconnectAttempt.set(0);
        subscribe();
    }

    private void subscribe() {
        for (int i = 0; i < symbols.size(); i++) {
            var symbol = symbols.get(i);
            var contract = new Contract();
            contract.symbol(symbol);
            contract.secType("STK");
            contract.currency("USD");
            contract.exchange("SMART");

            int l1ReqId = L1_BASE_ID + i;
            int l2ReqId = L2_BASE_ID + i;

            marketDataHandler.registerSymbol(l1ReqId, symbol);
            depthBookHandler.registerSymbol(l2ReqId, symbol);

            LOG.info("Subscribing L1 (reqId=%d) and L2 (reqId=%d) for %s".formatted(l1ReqId, l2ReqId, symbol));
            clientSocket.reqMktData(l1ReqId, contract, "", false, false, null);
            clientSocket.reqMktDepth(l2ReqId, contract, depthLevels, true, null);
        }
    }

    public void disconnect() {
        if (clientSocket == null) {
            return;
        }

        for (int i = 0; i < symbols.size(); i++) {
            try {
                clientSocket.cancelMktData(L1_BASE_ID + i);
                clientSocket.cancelMktDepth(L2_BASE_ID + i, true);
            } catch (Exception e) {
                LOG.log(Level.FINE, "Error cancelling subscription", e);
            }
        }

        connected.set(false);
        clientSocket.eDisconnect();
        LOG.info("Disconnected from IBKR.");
    }

    private void scheduleReconnect() {
        int attempt = reconnectAttempt.getAndIncrement();
        long delay = Math.min((long) Math.pow(2, attempt) * 1000, MAX_RECONNECT_DELAY_MS);

        LOG.warning("Connection lost. Reconnecting in %dms (attempt %d)...".formatted(delay, attempt + 1));

        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(() -> {
            clientId = (int) (System.currentTimeMillis() % 10000);
            doConnect();
        });
    }

    // ── EWrapper: Connection ─────────────────────────────────────────────

    @Override
    public void connectAck() {
        LOG.info("Connection acknowledged.");
        if (clientSocket.isAsyncEConnect()) {
            clientSocket.startAPI();
        }
    }

    @Override
    public void nextValidId(int orderId) {
        LOG.info("Connection ready. nextValidId=%d".formatted(orderId));
        connectedLatch.countDown();
    }

    @Override
    public void connectionClosed() {
        LOG.warning("Connection closed by server.");
        if (connected.getAndSet(false)) {
            scheduleReconnect();
        }
    }

    // ── EWrapper: Market Data ────────────────────────────────────────────

    @Override
    public void tickPrice(int reqId, int tickType, double price, TickAttrib attribs) {
        marketDataHandler.handleTickPrice(reqId, tickType, price);
    }

    @Override
    public void tickSize(int reqId, int tickType, Decimal size) {
        marketDataHandler.handleTickSize(reqId, tickType, size);
    }

    @Override
    public void tickString(int reqId, int tickType, String value) {
        marketDataHandler.handleTickString(reqId, tickType, value);
    }

    // ── EWrapper: Market Depth ───────────────────────────────────────────

    @Override
    public void updateMktDepth(int reqId, int position, int operation, int side, double price, Decimal size) {
        long longSize = size.isValid() ? size.longValue() : 0L;
        depthBookHandler.handleUpdateMktDepth(reqId, position, operation, side, price, longSize);
    }

    @Override
    public void updateMktDepthL2(int reqId, int position, String marketMaker, int operation,
                                 int side, double price, Decimal size, boolean isSmartDepth) {
        long longSize = size.isValid() ? size.longValue() : 0L;
        depthBookHandler.handleUpdateMktDepthL2(reqId, position, marketMaker, operation, side, price, longSize);
    }

    // ── EWrapper: Error Handling ─────────────────────────────────────────

    @Override
    public void error(Exception e) {
        LOG.log(Level.SEVERE, "TWS exception", e);
    }

    @Override
    public void error(String str) {
        LOG.severe("TWS error: " + str);
    }

    @Override
    public void error(int id, long apiCode, int errorCode, String errorMsg, String advancedOrderRejectJson) {
        if (WARN_ONLY_ERRORS.contains(errorCode)) {
            LOG.warning("[%d] %d — %s".formatted(id, errorCode, errorMsg));
            return;
        }
        if (errorCode == 502 || errorCode == 504) {
            LOG.severe("[%d] %d — %s. Ensure IB Gateway/TWS is running and accepting connections.".formatted(id, errorCode, errorMsg));
            return;
        }
        LOG.severe("[%d] %d — %s".formatted(id, errorCode, errorMsg));
    }

    // ── EWrapper: No-op implementations ──────────────────────────────────

    @Override public void tickOptionComputation(int i, int i1, int i2, double v, double v1, double v2, double v3, double v4, double v5, double v6, double v7) {}
    @Override public void tickGeneric(int i, int i1, double v) {}
    @Override public void tickEFP(int i, int i1, double v, String s, double v1, int i2, String s1, double v2, double v3) {}
    @Override public void orderStatus(int i, String s, Decimal decimal, Decimal decimal1, double v, long l1, int i2, double v1, int i3, String s1, double v2) {}
    @Override public void openOrder(int i, Contract contract, Order order, OrderState orderState) {}
    @Override public void openOrderEnd() {}
    @Override public void updateAccountValue(String s, String s1, String s2, String s3) {}
    @Override public void updatePortfolio(Contract contract, Decimal decimal, double v, double v1, double v2, double v3, double v4, String s) {}
    @Override public void updateAccountTime(String s) {}
    @Override public void accountDownloadEnd(String s) {}
    @Override public void contractDetails(int i, ContractDetails contractDetails) {}
    @Override public void bondContractDetails(int i, ContractDetails contractDetails) {}
    @Override public void contractDetailsEnd(int i) {}
    @Override public void execDetails(int i, Contract contract, Execution execution) {}
    @Override public void execDetailsEnd(int i) {}
    @Override public void updateNewsBulletin(int i, int i1, String s, String s1) {}
    @Override public void managedAccounts(String s) {}
    @Override public void receiveFA(int i, String s) {}
    @Override public void historicalData(int i, Bar bar) {}
    @Override public void historicalDataEnd(int i, String s, String s1) {}
    @Override public void scannerParameters(String s) {}
    @Override public void scannerData(int i, int i1, ContractDetails contractDetails, String s, String s1, String s2, String s3) {}
    @Override public void scannerDataEnd(int i) {}
    @Override public void realtimeBar(int i, long l, double v, double v1, double v2, double v3, Decimal decimal, Decimal decimal1, int i1) {}
    @Override public void currentTime(long l) {}
    @Override public void fundamentalData(int i, String s) {}
    @Override public void deltaNeutralValidation(int i, DeltaNeutralContract deltaNeutralContract) {}
    @Override public void tickSnapshotEnd(int i) {}
    @Override public void marketDataType(int i, int i1) {}
    @Override public void commissionAndFeesReport(CommissionAndFeesReport report) {}
    @Override public void position(String s, Contract contract, Decimal decimal, double v) {}
    @Override public void positionEnd() {}
    @Override public void accountSummary(int i, String s, String s1, String s2, String s3) {}
    @Override public void accountSummaryEnd(int i) {}
    @Override public void verifyMessageAPI(String s) {}
    @Override public void verifyCompleted(boolean b, String s) {}
    @Override public void verifyAndAuthMessageAPI(String s, String s1) {}
    @Override public void verifyAndAuthCompleted(boolean b, String s) {}
    @Override public void displayGroupList(int i, String s) {}
    @Override public void displayGroupUpdated(int i, String s) {}
    @Override public void positionMulti(int i, String s, String s1, Contract contract, Decimal decimal, double v) {}
    @Override public void positionMultiEnd(int i) {}
    @Override public void accountUpdateMulti(int i, String s, String s1, String s2, String s3, String s4) {}
    @Override public void accountUpdateMultiEnd(int i) {}
    @Override public void securityDefinitionOptionalParameter(int i, String s, int i1, String s1, String s2, java.util.Set<String> set, java.util.Set<Double> set1) {}
    @Override public void securityDefinitionOptionalParameterEnd(int i) {}
    @Override public void softDollarTiers(int i, SoftDollarTier[] softDollarTiers) {}
    @Override public void familyCodes(FamilyCode[] familyCodes) {}
    @Override public void symbolSamples(int i, ContractDescription[] contractDescriptions) {}
    @Override public void historicalDataUpdate(int i, Bar bar) {}
    @Override public void rerouteMktDataReq(int i, int i1, String s) {}
    @Override public void rerouteMktDepthReq(int i, int i1, String s) {}
    @Override public void marketRule(int i, PriceIncrement[] priceIncrements) {}
    @Override public void pnl(int i, double v, double v1, double v2) {}
    @Override public void pnlSingle(int i, Decimal decimal, double v, double v1, double v2, double v3) {}
    @Override public void historicalTicks(int i, java.util.List<HistoricalTick> list, boolean b) {}
    @Override public void historicalTicksBidAsk(int i, java.util.List<HistoricalTickBidAsk> list, boolean b) {}
    @Override public void historicalTicksLast(int i, java.util.List<HistoricalTickLast> list, boolean b) {}
    @Override public void tickByTickAllLast(int i, int i1, long l, double v, Decimal decimal, TickAttribLast tickAttribLast, String s, String s1) {}
    @Override public void tickByTickBidAsk(int i, long l, double v, double v1, Decimal decimal, Decimal decimal1, TickAttribBidAsk tickAttribBidAsk) {}
    @Override public void tickByTickMidPoint(int i, long l, double v) {}
    @Override public void orderBound(long l, int i, int i1) {}
    @Override public void completedOrder(Contract contract, Order order, OrderState orderState) {}
    @Override public void completedOrdersEnd() {}
    @Override public void replaceFAEnd(int i, String s) {}
    @Override public void wshMetaData(int i, String s) {}
    @Override public void wshEventData(int i, String s) {}
    @Override public void historicalSchedule(int i, String s, String s1, String s2, java.util.List<HistoricalSession> historicalSessions) {}
    @Override public void userInfo(int i, String s) {}
    @Override public void tickNews(int i, long l, String s, String s1, String s2, String s3) {}
    @Override public void smartComponents(int i, Map<Integer, Map.Entry<String, Character>> map) {}
    @Override public void tickReqParams(int i, double v, String s, int i1) {}
    @Override public void newsProviders(NewsProvider[] newsProviders) {}
    @Override public void newsArticle(int i, int i1, String s) {}
    @Override public void historicalNews(int i, String s, String s1, String s2, String s3) {}
    @Override public void historicalNewsEnd(int i, boolean b) {}
    @Override public void headTimestamp(int i, String s) {}
    @Override public void histogramData(int i, java.util.List<HistogramEntry> list) {}
    @Override public void mktDepthExchanges(DepthMktDataDescription[] depthMktDataDescriptions) {}
    @Override public void currentTimeInMillis(long l) {}
    @Override public void orderStatusProtoBuf(com.ib.client.protobuf.OrderStatusProto.OrderStatus orderStatus) {}
    @Override public void openOrderProtoBuf(com.ib.client.protobuf.OpenOrderProto.OpenOrder openOrder) {}
    @Override public void openOrdersEndProtoBuf(com.ib.client.protobuf.OpenOrdersEndProto.OpenOrdersEnd openOrdersEnd) {}
    @Override public void errorProtoBuf(com.ib.client.protobuf.ErrorMessageProto.ErrorMessage errorMessage) {}
    @Override public void execDetailsProtoBuf(com.ib.client.protobuf.ExecutionDetailsProto.ExecutionDetails executionDetails) {}
    @Override public void execDetailsEndProtoBuf(com.ib.client.protobuf.ExecutionDetailsEndProto.ExecutionDetailsEnd executionDetailsEnd) {}
}
