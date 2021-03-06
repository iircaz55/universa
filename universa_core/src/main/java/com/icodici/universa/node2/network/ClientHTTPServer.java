/*
 * Copyright (c) 2017 Sergey Chernov, iCodici S.n.C, All Rights Reserved
 *
 * Written by Sergey Chernov <real.sergeych@gmail.com>
 *
 */

package com.icodici.universa.node2.network;

import com.icodici.crypto.PrivateKey;
import com.icodici.universa.ErrorRecord;
import com.icodici.universa.Errors;
import com.icodici.universa.HashId;
import com.icodici.universa.contract.Contract;
import com.icodici.universa.contract.Parcel;
import com.icodici.universa.node.ItemResult;
import com.icodici.universa.node.ItemState;
import com.icodici.universa.node.network.BasicHTTPService;
import com.icodici.universa.node2.*;
import net.sergeych.tools.Binder;
import net.sergeych.tools.BufferedLogger;
import net.sergeych.utils.Bytes;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

public class ClientHTTPServer extends BasicHttpServer {


    private static final String API_VERSION = "3.1.0";

    private final BufferedLogger log;
    private ItemCache cache;
    private ParcelCache parcelCache;
    private NetConfig netConfig;
    private Config config;

    private boolean localCors = false;

    private ExecutorService es = Executors.newFixedThreadPool(40);

    public ClientHTTPServer(PrivateKey privateKey, int port, BufferedLogger logger) throws IOException {
        super(privateKey, port, 32, logger);
        log = logger;

        addSecureEndpoint("status", (params, session) -> Binder.of(
                "status", "initializing",
                "log", log.getLast(10)
        ));

        on("/contracts", (request, response) -> {
            String encodedString = request.getPath().substring(11);

            // this is a bug - path has '+' decoded as ' '
            encodedString = encodedString.replace(' ', '+');

            byte[] data = null;
            if (encodedString.equals("cache_test")) {
                data = "the cache test data".getBytes();
            } else {
                HashId id = HashId.withDigest(encodedString);
                if (cache != null) {
                    Contract c = (Contract) cache.get(id);
                    if (c != null) {
                        data = c.getPackedTransaction();
                    }
                }
            }
            if (data != null) {
                // contracts are immutable: cache forever
                Binder hh = response.getHeaders();
                hh.put("Expires", "Thu, 31 Dec 2037 23:55:55 GMT");
                hh.put("Cache-Control", "max-age=315360000");
                response.setBody(data);
            } else
                response.setResponseCode(404);
        });

        on("/parcels", (request, response) -> {
            String encodedString = request.getPath().substring(9);

            // this is a bug - path has '+' decoded as ' '
            encodedString = encodedString.replace(' ', '+');

            byte[] data = null;
            if (encodedString.equals("cache_test")) {
                data = "the cache test data".getBytes();
            } else {
                HashId id = HashId.withDigest(encodedString);
                if (parcelCache != null) {
                    Parcel p = (Parcel) parcelCache.get(id);
                    if (p != null) {
                        data = p.pack();
                    }
                }
            }
            if (data != null) {
                // contracts are immutable: cache forever
                Binder hh = response.getHeaders();
                hh.put("Expires", "Thu, 31 Dec 2037 23:55:55 GMT");
                hh.put("Cache-Control", "max-age=315360000");
                response.setBody(data);
            } else
                response.setResponseCode(404);
        });

        addEndpoint("/network", (Binder params, Result result) -> {
            if (networkData == null) {
                List<Binder> nodes = new ArrayList<Binder>();
                result.putAll(
                        "version", Main.NODE_VERSION,
                        "nodes", nodes
                );
                if (netConfig != null) {
                    netConfig.forEachNode(node -> {
                        nodes.add(Binder.of(
                                "url", node.publicUrlString(),
                                "key", node.getPublicKey().pack()
                        ));
                    });
                }
            }

        });

        addSecureEndpoint("getStats", this::getStats);
        addSecureEndpoint("getState", this::getState);
        addSecureEndpoint("getParcelProcessingState", this::getParcelProcessingState);
        addSecureEndpoint("approve", this::approve);
        addSecureEndpoint("approveParcel", this::approveParcel);
        addSecureEndpoint("startApproval", this::startApproval);
        addSecureEndpoint("throw_error", this::throw_error);
    }

    @Override
    public void shutdown() {
        es.shutdown();
        node.shutdown();
        super.shutdown();
    }

    private Binder throw_error(Binder binder, Session session) throws IOException {
        throw new IOException("just a test");
    }

    private ItemResult itemResultOfError(Errors error, String object, String message) {
        Binder binder = new Binder();
        binder.put("state",ItemState.UNDEFINED.name());
        binder.put("haveCopy",false);
        binder.put("createdAt", new Date());
        binder.put("expiresAt", new Date());
        ArrayList<ErrorRecord> errorRecords = new ArrayList<>();
        errorRecords.add(new ErrorRecord(error,object,message));
        binder.put("errors",errorRecords);
        return new ItemResult(binder);
    }

    private Binder approve(Binder params, Session session) throws IOException, Quantiser.QuantiserException {
        checkNode(session);
        if(!config.getKeysWhiteList().contains(session.getPublicKey())) {
            System.out.println("approve ERROR: no payment");

            return Binder.of(
                    "itemResult", itemResultOfError(Errors.BAD_CLIENT_KEY,"approve", "command needs client key from whitelist"));
        }

        try {
            return Binder.of(
                    "itemResult",
                    node.registerItem(Contract.fromPackedTransaction(params.getBinaryOrThrow("packedItem")))
            );
        } catch (Exception e) {
            System.out.println("approve ERROR: " + e.getMessage());

            return Binder.of(
                    "itemResult", itemResultOfError(Errors.COMMAND_FAILED,"approve", e.getMessage()));
        }
    }

    private Binder approveParcel(Binder params, Session session) throws IOException, Quantiser.QuantiserException {
        checkNode(session);
        try {
    //        System.out.println("Request to approve parcel, package size: " + params.getBinaryOrThrow("packedItem").length);
            return Binder.of(
                    "result",
                    node.registerParcel(Parcel.unpack(params.getBinaryOrThrow("packedItem")))
            );
        } catch (Exception e) {
            System.out.println("approveParcel ERROR: " + e.getMessage());
            return Binder.of(
                    "itemResult", itemResultOfError(Errors.COMMAND_FAILED,"approveParcel", e.getMessage()));
        }
    }

    static AtomicInteger asyncStarts = new AtomicInteger();

    private Binder startApproval(final Binder params, Session session) throws IOException, Quantiser.QuantiserException {
        if(config == null || !config.getKeysWhiteList().contains(session.getPublicKey())) {
            System.out.println("startApproval ERROR: session key shoild be in the white list");

            return Binder.of(
                    "itemResult", itemResultOfError(Errors.BAD_CLIENT_KEY,"startApproval", "command needs client key from whitelist"));
        }

        int n = asyncStarts.incrementAndGet();
        AtomicInteger k = new AtomicInteger();
        params.getListOrThrow("packedItems").forEach((item) ->
                es.execute(() -> {
                    try {
                        checkNode(session);
                        System.out.println("Request to start registration #"+n+":"+k.incrementAndGet());
                        node.registerItem(Contract.fromPackedTransaction(((Bytes)item).toArray()));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                })
        );

        //TODO: return ItemResult
        return new Binder();
    }

    private Binder getState(Binder params, Session session) throws CommandFailedException {

        checkNode(session);
        try {
            return Binder.of("itemResult",
                    node.checkItem((HashId) params.get("itemId")));
        } catch (Exception e) {
            System.out.println("getState ERROR: " + e.getMessage());
            return Binder.of(
                    "itemResult", itemResultOfError(Errors.COMMAND_FAILED,"approveParcel", e.getMessage()));
        }
    }

    private Binder getStats(Binder params, Session session) throws CommandFailedException {

        checkNode(session);

        if(config == null || !config.getNetworkAdminKey().equals(session.getPublicKey())) {
            System.out.println("command needs admin key");
            return Binder.of(
                    "itemResult", itemResultOfError(Errors.BAD_CLIENT_KEY,"getStats", "command needs admin key"));
        }
        return node.provideStats();
    }

    private Binder getParcelProcessingState(Binder params, Session session) throws CommandFailedException {
        checkNode(session);
        try {
            return Binder.of("processingState",
                    node.checkParcelProcessingState((HashId) params.get("parcelId")));
        } catch (Exception e) {
            System.out.println("getParcelProcessingState ERROR: " + e.getMessage());
            //TODO: return processing state not String
            return Binder.of(
                    "processingState",
                    "getParcelProcessingState ERROR: " + e.getMessage()
            );
        }
    }

    private void checkNode(Session session) throws CommandFailedException {
        if (node == null) {
            throw new CommandFailedException(Errors.NOT_READY, "", "please call again after a while");
        }

        if(node.isSanitating()) {
            //WHILE NODE IS SANITATING IT COMMUNICATES WITH THE OTHER NODES ONLY
            if(netConfig.toList().stream().anyMatch(nodeInfo -> nodeInfo.getPublicKey().equals(session.getPublicKey())))
                return;

            throw new CommandFailedException(Errors.NOT_READY, "", "please call again after a while");
        }

    }

    static private Binder networkData = null;

    @Override
    public void on(String path, BasicHTTPService.Handler handler) {
        super.on(path, (request, response) -> {
            if (localCors) {
                Binder hh = response.getHeaders();
                hh.put("Access-Control-Allow-Origin", "*");
                hh.put("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                hh.put("Access-Control-Allow-Headers", "DNT,X-CustomHeader,Keep-Alive,User-Age  nt,X-Requested-With,If-Modified-Since,Cache-Control,Content-Type,Content-Range,Range");
                hh.put("Access-Control-Expose-Headers", "DNT,X-CustomHeader,Keep-Alive,User-Agent,X-Requested-With,If-Modified-Since,Cache-Control,Content-Type,Content-Range,Range");
            }
            handler.handle(request, response);
        });
    }

    private Node node;

    public ItemCache getCache() {
        return cache;
    }

    public void setCache(ItemCache cache) {
        this.cache = cache;
    }

    public ParcelCache getParcelCache() {
        return parcelCache;
    }

    public void setParcelCache(ParcelCache cache) {
        this.parcelCache = cache;
    }

    public void setNetConfig(NetConfig netConfig) {
        this.netConfig = netConfig;
    }

    public void setNode(Node node) {
        this.node = node;
    }

    public boolean isLocalCors() {
        return localCors;
    }

    public void setLocalCors(boolean localCors) {
        this.localCors = localCors;
    }

    public void setConfig(Config config) {
        this.config = config;
    }

    //    @Override
//    public void start() throws Exception {
//        super.start();
//        addSecureEndpoint("state", (params) -> getState());
//    }
//
//    private Binder getState(Binder params) {
//        response.set("status", "establishing");
//    }
}
