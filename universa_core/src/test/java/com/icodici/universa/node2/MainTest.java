/*
 * Copyright (c) 2017 Sergey Chernov, iCodici S.n.C, All Rights Reserved
 *
 * Written by Sergey Chernov <real.sergeych@gmail.com>
 *
 */

package com.icodici.universa.node2;

import com.icodici.crypto.PrivateKey;
import com.icodici.crypto.PublicKey;
import com.icodici.db.DbPool;
import com.icodici.db.PooledDb;
import com.icodici.universa.Approvable;
import com.icodici.universa.HashId;
import com.icodici.universa.contract.*;
import com.icodici.universa.contract.permissions.ChangeOwnerPermission;
import com.icodici.universa.contract.permissions.ModifyDataPermission;
import com.icodici.universa.contract.roles.ListRole;
import com.icodici.universa.contract.roles.Role;
import com.icodici.universa.contract.roles.RoleLink;
import com.icodici.universa.contract.roles.SimpleRole;
import com.icodici.universa.node.*;
import com.icodici.universa.node.network.TestKeys;
import com.icodici.universa.node2.network.*;
import net.sergeych.boss.Boss;
import net.sergeych.tools.Binder;
import net.sergeych.tools.BufferedLogger;
import net.sergeych.tools.Do;
import net.sergeych.utils.Base64;
import net.sergeych.utils.Bytes;
import net.sergeych.utils.LogPrinter;
import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;

import javax.xml.crypto.Data;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.net.*;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

@Ignore("start it manually")
public class MainTest {


    @After
    public void tearDown() throws Exception {
        LogPrinter.showDebug(false);
    }

    @Test
    public void startNode() throws Exception {
        String path = new File("src/test_node_config_v2/node1").getAbsolutePath();
        System.out.println(path);
        String[] args = new String[]{"--test", "--config", path, "--nolog"};
        Main main = new Main(args);
        main.waitReady();
        BufferedLogger l = main.logger;

        Client client = new Client(
                "http://localhost:8080",
                TestKeys.privateKey(3),
                main.getNodePublicKey(),
                null
        );

        Binder data = client.command("status");
        data.getStringOrThrow("status");
//        assertThat(data.getListOrThrow("log").size(), greaterThan(3));
        BasicHttpClient.Answer a = client.request("ping");
        assertEquals("200: {ping=pong}", a.toString());


        Contract c = new Contract();
        c.setIssuerKeys(TestKeys.publicKey(3));
        c.addSignerKey(TestKeys.privateKey(3));
        c.registerRole(new RoleLink("owner", "issuer"));
        c.registerRole(new RoleLink("creator", "issuer"));
        c.setExpiresAt(ZonedDateTime.now().plusDays(2));
        byte[] sealed = c.seal();
//        Bytes.dump(sealed);

        Contract c1 = new Contract(sealed);
        assertArrayEquals(c.getLastSealedBinary(), c1.getLastSealedBinary());

        main.cache.put(c);
        assertNotNull(main.cache.get(c.getId()));

        URL url = new URL("http://localhost:8080/contracts/" + c.getId().toBase64String());
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");
        assertEquals(200, con.getResponseCode());
        byte[] data2 = Do.read(con.getInputStream());

        assertArrayEquals(c.getPackedTransaction(), data2);

        url = new URL("http://localhost:8080/network");
        con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");
        assertEquals(200, con.getResponseCode());
        Binder bres = Boss.unpack((Do.read(con.getInputStream())))
                .getBinderOrThrow("response");
        List<Binder> ni = bres.getBinders("nodes");
        String pubUrls = ni.stream().map(x -> x.getStringOrThrow("url"))
                .collect(Collectors.toList())
                .toString();

        assertEquals("[http://localhost:8080, http://localhost:6002, http://localhost:6004, http://localhost:6006]", pubUrls);

        main.shutdown();
        main.logger.stopInterceptingStdOut();
        ;
        main.logger.getCopy().forEach(x -> System.out.println(x));
    }

    Main createMain(String name, boolean nolog) throws InterruptedException {
        return createMain(name,"",nolog);
    }

    Main createMain(String name, String postfix, boolean nolog) throws InterruptedException {
        String path = new File("src/test_node_config_v2" + postfix + "/" + name).getAbsolutePath();
        System.out.println(path);
        String[] args = new String[]{"--test", "--config", path, nolog ? "--nolog" : ""};

        List<Main> mm = new ArrayList<>();

        Thread thread = new Thread(() -> {
            try {
                Main m = new Main(args);
                m.config.setTransactionUnitsIssuerKeyData(Bytes.fromHex("1E 08 1C 01 00 01 C4 00 01 B9 C7 CB 1B BA 3C 30 80 D0 8B 29 54 95 61 41 39 9E C6 BB 15 56 78 B8 72 DC 97 58 9F 83 8E A0 B7 98 9E BB A9 1D 45 A1 6F 27 2F 61 E0 26 78 D4 9D A9 C2 2F 29 CB B6 F7 9F 97 60 F3 03 ED 5C 58 27 27 63 3B D3 32 B5 82 6A FB 54 EA 26 14 E9 17 B6 4C 5D 60 F7 49 FB E3 2F 26 52 16 04 A6 5E 6E 78 D1 78 85 4D CD 7B 71 EB 2B FE 31 39 E9 E0 24 4F 58 3A 1D AE 1B DA 41 CA 8C 42 2B 19 35 4B 11 2E 45 02 AD AA A2 55 45 33 39 A9 FD D1 F3 1F FA FE 54 4C 2E EE F1 75 C9 B4 1A 27 5C E9 C0 42 4D 08 AD 3E A2 88 99 A3 A2 9F 70 9E 93 A3 DF 1C 75 E0 19 AB 1F E0 82 4D FF 24 DA 5D B4 22 A0 3C A7 79 61 41 FD B7 02 5C F9 74 6F 2C FE 9A DD 36 44 98 A2 37 67 15 28 E9 81 AC 40 CE EF 05 AA 9E 36 8F 56 DA 97 10 E4 10 6A 32 46 16 D0 3B 6F EF 80 41 F3 CC DA 14 74 D1 BF 63 AC 28 E0 F1 04 69 63 F7"));
                m.config.getKeysWhiteList().add(m.config.getTransactionUnitsIssuerKey());
                m.waitReady();
                mm.add(m);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        thread.setName("Node Server: " + name);
        thread.start();

        while (mm.size() == 0) {
            Thread.sleep(100);
        }
        return mm.get(0);
    }

    Main createMainFromDb(String dbUrl, boolean nolog) throws InterruptedException {
        String[] args = new String[]{"--test","--database", dbUrl, nolog ? "--nolog" : ""};

        List<Main> mm = new ArrayList<>();

        Thread thread = new Thread(() -> {
            try {
                Main m = new Main(args);
                m.config.setTransactionUnitsIssuerKeyData(Bytes.fromHex("1E 08 1C 01 00 01 C4 00 01 B9 C7 CB 1B BA 3C 30 80 D0 8B 29 54 95 61 41 39 9E C6 BB 15 56 78 B8 72 DC 97 58 9F 83 8E A0 B7 98 9E BB A9 1D 45 A1 6F 27 2F 61 E0 26 78 D4 9D A9 C2 2F 29 CB B6 F7 9F 97 60 F3 03 ED 5C 58 27 27 63 3B D3 32 B5 82 6A FB 54 EA 26 14 E9 17 B6 4C 5D 60 F7 49 FB E3 2F 26 52 16 04 A6 5E 6E 78 D1 78 85 4D CD 7B 71 EB 2B FE 31 39 E9 E0 24 4F 58 3A 1D AE 1B DA 41 CA 8C 42 2B 19 35 4B 11 2E 45 02 AD AA A2 55 45 33 39 A9 FD D1 F3 1F FA FE 54 4C 2E EE F1 75 C9 B4 1A 27 5C E9 C0 42 4D 08 AD 3E A2 88 99 A3 A2 9F 70 9E 93 A3 DF 1C 75 E0 19 AB 1F E0 82 4D FF 24 DA 5D B4 22 A0 3C A7 79 61 41 FD B7 02 5C F9 74 6F 2C FE 9A DD 36 44 98 A2 37 67 15 28 E9 81 AC 40 CE EF 05 AA 9E 36 8F 56 DA 97 10 E4 10 6A 32 46 16 D0 3B 6F EF 80 41 F3 CC DA 14 74 D1 BF 63 AC 28 E0 F1 04 69 63 F7"));
                m.config.getKeysWhiteList().add(m.config.getTransactionUnitsIssuerKey());
                m.waitReady();
                mm.add(m);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        thread.setName("Node Server: " + dbUrl);
        thread.start();

        while (mm.size() == 0) {
            Thread.sleep(100);
        }
        return mm.get(0);
    }

    @Test
    public void networkReconfigurationTestSerial() throws Exception {

        //create 4 nodes from config file. 3 know each other. 4th knows everyone. nobody knows 4th
        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            mm.add(createMain("node" + (i + 1),"_dynamic_test", false));
        }
        //shutdown nodes
        for(Main m : mm) {
            m.shutdown();
        }
        mm.clear();

        //initialize same nodes from db
        List<String> dbUrls = new ArrayList<>();
        Thread.sleep(1000);
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t1");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t2");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t3");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t4");


        for (int i = 0; i < 4; i++) {
            mm.add(createMainFromDb(dbUrls.get(i), false));
        }


        PrivateKey myKey = TestKeys.privateKey(3);
        Main main = mm.get(3);

        PrivateKey universaKey = new PrivateKey(Do.read("./src/test_contracts/keys/tu_key.private.unikey"));
        Contract contract = new Contract(universaKey);
        contract.seal();
        assertTrue(contract.isOk());


        //registering with UNKNOWN node. Shouldn't succeed
        int attempts = 3;
        Client client = new Client(myKey, main.myInfo, null);
        ItemResult rr = client.register(contract.getPackedTransaction(), 15000);
        while (attempts-- > 0) {
            rr = client.getState(contract.getId());
            System.out.println(rr);
            Thread.currentThread().sleep(1000);
            if (!rr.state.isPending())
                break;
        }
        assertEquals(rr.state,ItemState.PENDING_POSITIVE);

        contract = new Contract(universaKey);
        contract.seal();
        assertTrue(contract.isOk());

        //registering with KNOWN node. Should succeed
        Client clientKnown = new Client(myKey, mm.get(0).myInfo, null);
        clientKnown.register(contract.getPackedTransaction(), 15000);
        while (true) {
            rr = clientKnown.getState(contract.getId());
            Thread.currentThread().sleep(50);
            if (!rr.state.isPending())
                break;
        }
        assertEquals(rr.state,ItemState.APPROVED);


        //Make 4th node KNOWN to other nodes
        for(int i = 0; i < 3; i++) {
            mm.get(i).node.addNode(main.myInfo);
        }

        contract = new Contract(universaKey);
        contract.seal();
        assertTrue(contract.isOk());

//        main.setUDPVerboseLevel(DatagramAdapter.VerboseLevel.DETAILED);
//        mm.get(0).setUDPVerboseLevel(DatagramAdapter.VerboseLevel.DETAILED);

        client.register(contract.getPackedTransaction(), 15000);
        while (true) {
            rr = client.getState(contract.getId());
            Thread.currentThread().sleep(50);
            if (!rr.state.isPending())
                break;
        }
        assertEquals(rr.state,ItemState.APPROVED);
//        main.setUDPVerboseLevel(DatagramAdapter.VerboseLevel.NOTHING);
//        mm.get(0).setUDPVerboseLevel(DatagramAdapter.VerboseLevel.NOTHING);



        //Make 4th node UNKNOWN to other nodes
        for(int i = 0; i < 3; i++) {
            mm.get(i).node.removeNode(main.myInfo);
        }

        contract = new Contract(universaKey);
        contract.seal();
        assertTrue(contract.isOk());

        //registering with UNKNOWN node. Shouldn't succeed
        attempts = 3;
        rr = client.register(contract.getPackedTransaction(), 15000);
        while (attempts-- > 0) {
            rr = client.getState(contract.getId());
            Thread.currentThread().sleep(1000);
            if (!rr.state.isPending())
                break;
        }
        assertEquals(rr.state,ItemState.PENDING_POSITIVE);

        contract = new Contract(universaKey);
        contract.seal();
        assertTrue(contract.isOk());

        //registering with KNOWN node. Should succeed
        clientKnown.register(contract.getPackedTransaction(), 15000);
        while (true) {
            rr = clientKnown.getState(contract.getId());
            Thread.currentThread().sleep(50);
            if (!rr.state.isPending())
                break;
        }
        assertEquals(rr.state,ItemState.APPROVED);


        for(Main m : mm) {
            m.shutdown();
        }

    }

    @Test
    public void networkReconfigurationTestParallel() throws Exception {

        //create 4 nodes from config file. 3 know each other. 4th knows everyone. nobody knows 4th
        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            mm.add(createMain("node" + (i + 1), "_dynamic_test", false));
        }
        //shutdown nodes
        for (Main m : mm) {
            m.shutdown();
        }
        mm.clear();

        //initialize same nodes from db
        List<String> dbUrls = new ArrayList<>();
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t1");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t2");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t3");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t4");

        Random rand = new Random();
        rand.setSeed(new Date().getTime());


        final ArrayList<Integer> clientSleeps = new ArrayList<>();
        final ArrayList<Integer> nodeSleeps = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            mm.add(createMainFromDb(dbUrls.get(i), false));
            nodeSleeps.add(rand.nextInt(100));
        }


        PrivateKey myKey = TestKeys.privateKey(3);


        final ArrayList<Client> clients = new ArrayList<>();
        final ArrayList<Integer> clientNodes = new ArrayList<>();
        final ArrayList<Contract> contracts = new ArrayList<>();
        final ArrayList<Parcel> parcels = new ArrayList<>();
        final ArrayList<Boolean> contractsApproved = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            Contract contract = new Contract(myKey);
            contract.seal();
            assertTrue(contract.isOk());
            contracts.add(contract);
            contractsApproved.add(false);
            NodeInfo info = mm.get(rand.nextInt(3)).myInfo;
            clientNodes.add(info.getNumber());
            Client client = new Client(TestKeys.privateKey(i), info, null);
            clients.add(client);
            clientSleeps.add(rand.nextInt(100));
            Parcel parcel = createParcelWithFreshTU(client,contract,Do.listOf(myKey));
            parcels.add(parcel);
        }
        Semaphore semaphore = new Semaphore(-39);
        final AtomicInteger atomicInteger = new AtomicInteger(40);
        for (int i = 0; i < 40; i++) {
            int finalI = i;
            Thread th = new Thread(() -> {
                try {
                    //Thread.sleep(clientSleeps.get(finalI));
                    Thread.sleep(clientSleeps.get(finalI));
                    Contract contract = contracts.get(finalI);
                    Client client = clients.get(finalI);
                    System.out.println("Register item " + contract.getId().toBase64String() + " @ node #" + clientNodes.get(finalI));
                    client.registerParcel(parcels.get(finalI).pack(), 15000);
                    ItemResult rr;
                    while (true) {
                        rr = client.getState(contract.getId());
                        Thread.currentThread().sleep(50);
                        if (!rr.state.isPending())
                            break;
                    }
                    assertEquals(rr.state, ItemState.APPROVED);
                    semaphore.release();
                    atomicInteger.decrementAndGet();
                    contractsApproved.set(finalI, true);
                } catch (ClientError clientError) {
                    clientError.printStackTrace();
                    fail(clientError.getMessage());
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    fail(e.getMessage());
                } catch (IOException e) {
                    e.printStackTrace();
                    fail(e.getMessage());
                }

            });
            th.start();
        }

        for (int i = 0; i < 3; i++) {
            int finalI = i;
            Thread th = new Thread(() -> {
                try {
                    //Thread.sleep(nodeSleeps.get(finalI));
                    Thread.sleep(nodeSleeps.get(finalI)  );
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    fail(e.getMessage());
                }
                System.out.println("Adding new node @ node #" + (finalI + 1));
                mm.get(finalI).node.addNode(mm.get(3).myInfo);
                System.out.println("Done new node @ node #" + (finalI + 1));

            });
            th.start();
        }


        if (!semaphore.tryAcquire(15, TimeUnit.SECONDS)) {
            for (int i = 0; i < contractsApproved.size(); i++) {
                if (!contractsApproved.get(i)) {
                    System.out.println("Stuck item:" + contracts.get(i).getId().toBase64String());
                }
            }

            System.out.print("Client sleeps: ");
            for (Integer s : clientSleeps) {
                System.out.print(s + ", ");
            }
            System.out.println();


            System.out.print("Node sleeps: ");
            for (Integer s : nodeSleeps) {
                System.out.print(s + ", ");
            }
            System.out.println();

            fail("Items stuck: " + atomicInteger.get());
        }


        for (Main m : mm) {
            m.shutdown();
        }
        System.gc();

    }


    @Test
    public void reconfigurationContractTest() throws Exception {

//        PrivateKey reconfigKey = new PrivateKey(Do.read("./src/test_contracts/keys/reconfig_key.private.unikey"));
//        String string = new Bytes(reconfigKey.getPublicKey().pack()).toHex();
//        System.out.println(string);

        PrivateKey issuerKey = new PrivateKey(Do.read("./src/test_contracts/keys/reconfig_key.private.unikey"));


        List<Main> mm = new ArrayList<>();
        List<PrivateKey> nodeKeys = new ArrayList<>();
        List<PrivateKey> nodeKeysNew = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            mm.add(createMain("node" + (i + 1), "_dynamic_test", false));
            if(i < 3)
                nodeKeys.add(new PrivateKey(Do.read("./src/test_node_config_v2_dynamic_test/node" + (i + 1) + "/tmp/node2_" + (i + 1) + ".private.unikey")));
            nodeKeysNew.add(new PrivateKey(Do.read("./src/test_node_config_v2_dynamic_test/node" + (i + 1) + "/tmp/node2_" + (i + 1) + ".private.unikey")));
        }

        List<NodeInfo> netConfig = mm.get(0).netConfig.toList();
        List<NodeInfo> netConfigNew = mm.get(3).netConfig.toList();

        for (Main m : mm) {
            m.shutdown();
        }
        mm.clear();

        Contract configContract = createNetConfigContract(netConfig,issuerKey);

        List<String> dbUrls = new ArrayList<>();
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t1");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t2");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t3");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t4");

        for (int i = 0; i < 4; i++) {
            mm.add(createMainFromDb(dbUrls.get(i), false));
        }

        Client client = new Client(TestKeys.privateKey(0), mm.get(0).myInfo, null);

        Parcel parcel = createParcelWithFreshTU(client, configContract,Do.listOf(issuerKey));
        client.registerParcel(parcel.pack(),15000);


        ItemResult rr;
        while (true) {

            rr = client.getState(configContract.getId());
            Thread.currentThread().sleep(50);
            if (!rr.state.isPending())
                break;
        }
        assertEquals(rr.state, ItemState.APPROVED);

        configContract = createNetConfigContract(configContract,netConfigNew,nodeKeys);

        parcel = createParcelWithFreshTU(client, configContract,nodeKeys);
        client.registerParcel(parcel.pack(),15000);
        while (true) {

            rr = client.getState(configContract.getId());
            Thread.currentThread().sleep(50);
            if (!rr.state.isPending())
                break;
        }
        assertEquals(rr.state, ItemState.APPROVED);
        Thread.sleep(1000);
        for (Main m : mm) {
            assertEquals(m.config.getPositiveConsensus(), 3);
        }
        configContract = createNetConfigContract(configContract,netConfig,nodeKeys);

        parcel = createParcelWithFreshTU(client, configContract,nodeKeys);
        client.registerParcel(parcel.pack(),15000);
        while (true) {

            rr = client.getState(configContract.getId());
            Thread.currentThread().sleep(50);
            if (!rr.state.isPending())
                break;
        }
        assertEquals(rr.state, ItemState.APPROVED);
        Thread.sleep(1000);
        for (Main m : mm) {
            assertEquals(m.config.getPositiveConsensus(), 2);
        }

        for (Main m : mm) {
            m.shutdown();
        }
    }


    private Contract createNetConfigContract(Contract contract, List<NodeInfo> netConfig, Collection<PrivateKey> currentConfigKeys) throws IOException {
        contract = contract.createRevision();
        ListRole listRole = new ListRole("owner");
        for(NodeInfo ni: netConfig) {
            SimpleRole role = new SimpleRole(ni.getName());
            contract.registerRole(role);
            role.addKeyRecord(new KeyRecord(ni.getPublicKey()));
            listRole.addRole(role);
        }
        listRole.setQuorum(netConfig.size()-1);
        contract.registerRole(listRole);
        contract.getStateData().set("net_config",netConfig);
        List<KeyRecord> creatorKeys = new ArrayList<>();
        for(PrivateKey key : currentConfigKeys) {
            creatorKeys.add(new KeyRecord(key.getPublicKey()));
            contract.addSignerKey(key);
        }
        contract.setCreator(creatorKeys);
        contract.seal();
        return contract;
    }
    private Contract createNetConfigContract(List<NodeInfo> netConfig,PrivateKey issuerKey) throws IOException {
        Contract contract = new Contract();
        contract.setIssuerKeys(issuerKey.getPublicKey());
        contract.registerRole(new RoleLink("creator", "issuer"));
        ListRole listRole = new ListRole("owner");
        for(NodeInfo ni: netConfig) {
            SimpleRole role = new SimpleRole(ni.getName());
            contract.registerRole(role);
            role.addKeyRecord(new KeyRecord(ni.getPublicKey()));
            listRole.addRole(role);
        }
        listRole.setQuorum(netConfig.size()-1);
        contract.registerRole(listRole);
        RoleLink ownerLink = new RoleLink("ownerlink","owner");
        ChangeOwnerPermission changeOwnerPermission = new ChangeOwnerPermission(ownerLink);
        HashMap<String,Object> fieldsMap = new HashMap<>();
        fieldsMap.put("net_config",null);
        Binder modifyDataParams = Binder.of("fields",fieldsMap);
        ModifyDataPermission modifyDataPermission = new ModifyDataPermission(ownerLink,modifyDataParams);
        contract.addPermission(changeOwnerPermission);
        contract.addPermission(modifyDataPermission);
        contract.setExpiresAt(ZonedDateTime.now().plusYears(40));
        contract.getStateData().set("net_config",netConfig);
        contract.addSignerKey(issuerKey);
        contract.seal();
        return contract;
    }

    @Test
    public void localNetwork() throws Exception {
        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            mm.add(createMain("node" + (i + 1), false));
        }

        Main main = mm.get(0);
//        assertEquals("http://localhost:8080", main.myInfo.internalUrlString());
//        assertEquals("http://localhost:8080", main.myInfo.publicUrlString());
        PrivateKey myKey = TestKeys.privateKey(3);

//        assertEquals(main.cache, main.node.getCache());
//        ItemCache c1 = main.cache;
//        ItemCache c2 = main.node.getCache();

//        Client client = new Client(myKey, main.myInfo, null);


        List<Contract> contractsForThreads = new ArrayList<>();
        int N = 100;
        int M = 2;
        float threshold = 1.2f;
        float ratio = 0;
        boolean createNewContracts = false;
//        assertTrue(singleContract.isOk());

//        ItemResult r = client.getState(singleContract.getId());
//        assertEquals(ItemState.UNDEFINED, r.state);
//        System.out.println(r);


        contractsForThreads = new ArrayList<>();
        for (int j = 0; j < M; j++) {
            Contract contract = new Contract(myKey);

            for (int k = 0; k < 10; k++) {
                Contract nc = new Contract(myKey);
                nc.seal();
                contract.addNewItems(nc);
            }
            contract.seal();
            assertTrue(contract.isOk());
            contractsForThreads.add(contract);

//            ItemResult r = client.getState(contract.getId());
//            assertEquals(ItemState.UNDEFINED, r.state);
//            System.out.println(r);
        }

        Contract singleContract = new Contract(myKey);

        for (int k = 0; k < 10; k++) {
            Contract nc = new Contract(myKey);
            nc.seal();
            singleContract.addNewItems(nc);
        }
        singleContract.seal();

        // register


        for (int i = 0; i < N; i++) {

            if (createNewContracts) {
                contractsForThreads = new ArrayList<>();
                for (int j = 0; j < M; j++) {
                    Contract contract = new Contract(myKey);

                    for (int k = 0; k < 10; k++) {
                        Contract nc = new Contract(myKey);
                        nc.seal();
                        contract.addNewItems(nc);
                    }
                    contract.seal();
                    assertTrue(contract.isOk());
                    contractsForThreads.add(contract);


                }

                singleContract = new Contract(myKey);

                for (int k = 0; k < 10; k++) {
                    Contract nc = new Contract(myKey);
                    nc.seal();
                    singleContract.addNewItems(nc);
                }
                singleContract.seal();
            }

            long ts1;
            long ts2;
            Semaphore semaphore = new Semaphore(-(M - 1));

            ts1 = new Date().getTime();

            for (Contract c : contractsForThreads) {
                Thread thread = new Thread(() -> {

                    Client client = null;
                    try {
                        synchronized (this) {
                            client = new Client(myKey, main.myInfo, null);
                        }
                        long t = System.nanoTime();
                        ItemResult rr = null;
                        rr = client.register(c.getPackedTransaction(), 15000);
                        System.out.println("multi thread: " + rr + " time: " + ((System.nanoTime() - t) * 1e-9));

                    } catch (ClientError clientError) {
                        clientError.printStackTrace();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    semaphore.release();
                });
                thread.setName("Multi-thread register: " + c.getId().toString());
                thread.start();
            }

            semaphore.acquire();

            ts2 = new Date().getTime();

            long threadTime = ts2 - ts1;

            //

            ts1 = new Date().getTime();

            Contract finalSingleContract = singleContract;
            Thread thread = new Thread(() -> {
                long t = System.nanoTime();
                ItemResult rr = null;
                try {
                    Client client = null;
                    client = new Client(myKey, main.myInfo, null);
                    rr = client.register(finalSingleContract.getPackedTransaction(), 15000);
                    System.out.println("single thread: " + rr + " time: " + ((System.nanoTime() - t) * 1e-9));
                } catch (ClientError clientError) {
                    clientError.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                semaphore.release();
            });
            thread.setName("single-thread register: " + singleContract.getId().toString());
            thread.start();

            semaphore.acquire();

            ts2 = new Date().getTime();

            long singleTime = ts2 - ts1;

            System.out.println(threadTime * 1.0f / singleTime);
            ratio += threadTime * 1.0f / singleTime;
        }

        ratio /= N;
        System.out.println("average " + ratio);

        mm.forEach(x -> x.shutdown());
    }

    @Test
    @Ignore("This test nust be started manually")
    public void checkRealNetwork() throws Exception {

        int numThraeds = 8;
        List<PrivateKey> keys = new ArrayList<>();
        for (int j = 0; j < numThraeds; j++) {
            PrivateKey key = new PrivateKey(Do.read("./src/test_contracts/keys/" + j + ".private.unikey"));
            keys.add(key);
        }


        List<Contract> contractsForThreads = new ArrayList<>();
        for (int j = 0; j < numThraeds; j++) {
            PrivateKey key = keys.get(0);
            Contract contract = new Contract(key);

            for (int k = 0; k < 500; k++) {
                Contract nc = new Contract(key);
                nc.seal();
                contract.addNewItems(nc);
            }
            contract.seal();
            contractsForThreads.add(contract);
        }

        List<Thread> threadList = new ArrayList<>();
        long t1 = new Date().getTime();
        for (int i = 0; i < numThraeds; i++) {
            final int ii = i;
            Thread thread = new Thread(() -> {

                PrivateKey clientKey = keys.get(ii);
                Client client = null;
                try {
                    client = new Client("http://node-1-sel1.universa.io:8080", clientKey, null);

                    Contract c = contractsForThreads.get(ii);
                    ItemResult r = client.register(c.getPackedTransaction());

                    while (true) {
                        r = client.getState(c.getId());
                        System.out.println("-->? " + r);
                        Thread.currentThread().sleep(50);
                        if (!r.state.isPending())
                            break;
                    }
                } catch (ClientError clientError) {
                    clientError.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

            });
            thread.setName("thread register: " + i);
            threadList.add(thread);
            thread.start();
        }

        for (Thread thread : threadList)
            thread.join();

        long t2 = new Date().getTime();
        long multiTime = t2 - t1;
        System.out.println("time: " + multiTime + "ms");


//        r = client.getState(c.getId());
//        assertEquals(ItemState.UNDEFINED, r.state);
//        System.out.println(":: " + r);
//
//        LogPrinter.showDebug(true);
////        r = client.register(c.getLastSealedBinary());
//        System.out.println(r);
//
//        Client client = new Client(myKey, );
    }


    @Test
    public void localNetwork2() throws Exception {
        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            mm.add(createMain("node" + (i + 1), false));
        Main main = mm.get(0);
        assertEquals("http://localhost:8080", main.myInfo.internalUrlString());
        assertEquals("http://localhost:8080", main.myInfo.publicUrlString());
        PrivateKey myKey = new PrivateKey(Do.read("./src/test_contracts/keys/tu_key.private.unikey"));

        //Client client = new Client(myKey, main.myInfo, null);

        final long CONTRACTS_PER_THREAD = 60;
        final long THREADS_COUNT = 4;

        class TestRunnable implements Runnable {

            public int threadNum = 0;
            List<Contract> contractList = new ArrayList<>();
            Map<HashId, Contract> contractHashesMap = new ConcurrentHashMap<>();
            Client client = null;

            public void prepareClient() {
                try {
                    client = new Client(myKey, main.myInfo, null);
                } catch (Exception e) {
                    System.out.println("prepareClient exception: " + e.toString());
                }
            }

            public void prepareContracts() throws Exception {
                contractList = new ArrayList<>();
                for (int iContract = 0; iContract < CONTRACTS_PER_THREAD; ++iContract) {
                    Contract testContract = new Contract(myKey);
                    for (int i = 0; i < 10; i++) {
                        Contract nc = new Contract(myKey);
                        nc.seal();
                        testContract.addNewItems(nc);
                    }
                    testContract.seal();
                    assertTrue(testContract.isOk());
                    contractList.add(testContract);
                    contractHashesMap.put(testContract.getId(), testContract);
                }
            }

            private void sendContractsToRegister() throws Exception {
                for (int i = 0; i < contractList.size(); ++i) {
                    Contract contract = contractList.get(i);
                    client.register(contract.getPackedTransaction());
                }
            }

            private void waitForContracts() throws Exception {
                while (contractHashesMap.size() > 0) {
                    Thread.currentThread().sleep(300);
                    for (HashId id : contractHashesMap.keySet()) {
                        ItemResult itemResult = client.getState(id);
                        if (!itemResult.state.isPending())
                            contractHashesMap.remove(id);
                        else
                            break;
                    }
                }
            }

            @Override
            public void run() {
                try {
                    sendContractsToRegister();
                    waitForContracts();
                } catch (Exception e) {
                    System.out.println("runnable exception: " + e.toString());
                }
            }
        }

        System.out.println("singlethread test prepare...");
        TestRunnable runnableSingle = new TestRunnable();
        Thread threadSingle = new Thread(() -> {
            runnableSingle.threadNum = 0;
            runnableSingle.run();
        });
        runnableSingle.prepareClient();
        runnableSingle.prepareContracts();
        System.out.println("singlethread test start...");
        long t1 = new Date().getTime();
        threadSingle.start();
        threadSingle.join();
        long t2 = new Date().getTime();
        long dt = t2 - t1;
        long singleThreadTime = dt;
        System.out.println("singlethread test done!");

        System.out.println("multithread test prepare...");
        List<Thread> threadsList = new ArrayList<>();
        List<Thread> threadsPrepareList = new ArrayList<>();
        List<TestRunnable> runnableList = new ArrayList<>();
        for (int iThread = 0; iThread < THREADS_COUNT; ++iThread) {
            TestRunnable runnableMultithread = new TestRunnable();
            final int threadNum = iThread + 1;
            Thread threadMultiThread = new Thread(() -> {
                runnableMultithread.threadNum = threadNum;
                runnableMultithread.run();
            });
            Thread threadPrepareMultiThread = new Thread(() -> {
                try {
                    runnableMultithread.prepareContracts();
                } catch (Exception e) {
                    System.out.println("prepare exception: " + e.toString());
                }
            });
            runnableMultithread.prepareClient();
            threadsList.add(threadMultiThread);
            threadsPrepareList.add(threadPrepareMultiThread);
            runnableList.add(runnableMultithread);
        }
        for (Thread thread : threadsPrepareList)
            thread.start();
        for (Thread thread : threadsPrepareList)
            thread.join();
        Thread.sleep(500);
        System.out.println("multithread test start...");
        t1 = new Date().getTime();
        for (Thread thread : threadsList)
            thread.start();
        for (Thread thread : threadsList)
            thread.join();
        t2 = new Date().getTime();
        dt = t2 - t1;
        long multiThreadTime = dt;
        System.out.println("multithread test done!");

        Double tpsSingleThread = (double) CONTRACTS_PER_THREAD / (double) singleThreadTime * 1000.0;
        Double tpsMultiThread = (double) CONTRACTS_PER_THREAD * (double) THREADS_COUNT / (double) multiThreadTime * 1000.0;
        Double boostRate = tpsMultiThread / tpsSingleThread;

        System.out.println("\n === total ===");
        System.out.println("singleThread: " + (CONTRACTS_PER_THREAD) + " for " + singleThreadTime + "ms, tps=" + String.format("%.2f", tpsSingleThread));
        System.out.println("multiThread(N=" + THREADS_COUNT + "): " + (CONTRACTS_PER_THREAD * THREADS_COUNT) + " for " + multiThreadTime + "ms, tps=" + String.format("%.2f", tpsMultiThread));
        System.out.println("boostRate: " + String.format("%.2f", boostRate));
        System.out.println("\n");

        mm.forEach(x -> x.shutdown());
    }


    @Test
    public void localNetwork3() throws Exception {
        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            mm.add(createMain("node" + (i + 1), false));
        Main main = mm.get(0);
        assertEquals("http://localhost:8080", main.myInfo.internalUrlString());
        assertEquals("http://localhost:8080", main.myInfo.publicUrlString());
        PrivateKey myKey = new PrivateKey(Do.read("./src/test_contracts/keys/tu_key.private.unikey"));

        Set<PrivateKey> fromPrivateKeys = new HashSet<>();
        fromPrivateKeys.add(myKey);

        //Client client = new Client(myKey, main.myInfo, null);

        final long CONTRACTS_PER_THREAD = 10;
        final long THREADS_COUNT = 4;

        LogPrinter.showDebug(true);

        class TestRunnable implements Runnable {

            public int threadNum = 0;
            List<Parcel> contractList = new ArrayList<>();
            Map<HashId, Parcel> contractHashesMap = new ConcurrentHashMap<>();
            Client client = null;

            public void prepareClient() {
                try {
                    client = new Client(myKey, main.myInfo, null);
                } catch (Exception e) {
                    System.out.println("prepareClient exception: " + e.toString());
                }
            }

            public void prepareContracts() throws Exception {
                contractList = new ArrayList<>();
                for (int iContract = 0; iContract < CONTRACTS_PER_THREAD; ++iContract) {
                    Contract testContract = new Contract(myKey);
                    for (int i = 0; i < 10; i++) {
                        Contract nc = new Contract(myKey);
//                        nc.seal();
                        testContract.addNewItems(nc);
                    }
                    testContract.seal();
                    assertTrue(testContract.isOk());
                    Parcel parcel = createParcelWithFreshTU(client, testContract,Do.listOf(myKey));
                    contractList.add(parcel);
                    contractHashesMap.put(parcel.getId(), parcel);
                }
            }

            private void sendContractsToRegister() throws Exception {
                for (int i = 0; i < contractList.size(); ++i) {
                    Parcel parcel = contractList.get(i);
                    client.registerParcel(parcel.pack());
                }
            }

            private void waitForContracts() throws Exception {
                while (contractHashesMap.size() > 0) {
                    Thread.currentThread().sleep(100);
                    for (Parcel p : contractHashesMap.values()) {
                        ItemResult itemResult = client.getState(p.getPayloadContract().getId());
                        if (!itemResult.state.isPending())
                            contractHashesMap.remove(p.getId());
                    }
                }
            }

            @Override
            public void run() {
                try {
                    sendContractsToRegister();
                    waitForContracts();
                } catch (Exception e) {
                    System.out.println("runnable exception: " + e.toString());
                }
            }
        }

        System.out.println("singlethread test prepare...");
        TestRunnable runnableSingle = new TestRunnable();
        Thread threadSingle = new Thread(() -> {
            runnableSingle.threadNum = 0;
            runnableSingle.run();
        });
        runnableSingle.prepareClient();
        runnableSingle.prepareContracts();
        System.out.println("singlethread test start...");
        long t1 = new Date().getTime();
        threadSingle.start();
        threadSingle.join();
        long t2 = new Date().getTime();
        long dt = t2 - t1;
        long singleThreadTime = dt;
        System.out.println("singlethread test done!");

        System.out.println("multithread test prepare...");
        List<Thread> threadsList = new ArrayList<>();
        List<Thread> threadsPrepareList = new ArrayList<>();
        List<TestRunnable> runnableList = new ArrayList<>();
        for (int iThread = 0; iThread < THREADS_COUNT; ++iThread) {
            TestRunnable runnableMultithread = new TestRunnable();
            final int threadNum = iThread + 1;
            Thread threadMultiThread = new Thread(() -> {
                runnableMultithread.threadNum = threadNum;
                runnableMultithread.run();
            });
            Thread threadPrepareMultiThread = new Thread(() -> {
                try {
                    runnableMultithread.prepareContracts();
                } catch (Exception e) {
                    System.out.println("prepare exception: " + e.toString());
                }
            });
            runnableMultithread.prepareClient();
            threadsList.add(threadMultiThread);
            threadsPrepareList.add(threadPrepareMultiThread);
            runnableList.add(runnableMultithread);
        }
        for (Thread thread : threadsPrepareList)
            thread.start();
        for (Thread thread : threadsPrepareList)
            thread.join();
        Thread.sleep(500);
        System.out.println("multithread test start...");
        t1 = new Date().getTime();
        for (Thread thread : threadsList)
            thread.start();
        for (Thread thread : threadsList)
            thread.join();
        t2 = new Date().getTime();
        dt = t2 - t1;
        long multiThreadTime = dt;
        System.out.println("multithread test done!");

        Double tpsSingleThread = (double) CONTRACTS_PER_THREAD / (double) singleThreadTime * 1000.0;
        Double tpsMultiThread = (double) CONTRACTS_PER_THREAD * (double) THREADS_COUNT / (double) multiThreadTime * 1000.0;
        Double boostRate = tpsMultiThread / tpsSingleThread;

        System.out.println("\n === total ===");
        System.out.println("singleThread: " + (CONTRACTS_PER_THREAD) + " for " + singleThreadTime + "ms, tps=" + String.format("%.2f", tpsSingleThread));
        System.out.println("multiThread(N=" + THREADS_COUNT + "): " + (CONTRACTS_PER_THREAD * THREADS_COUNT) + " for " + multiThreadTime + "ms, tps=" + String.format("%.2f", tpsMultiThread));
        System.out.println("boostRate: " + String.format("%.2f", boostRate));
        System.out.println("\n");

        mm.forEach(x -> x.shutdown());
    }

    @Test
    public void checkVerbose() throws Exception {
        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            mm.add(createMain("node" + (i + 1), false));
        }

        Main main = mm.get(0);
        PrivateKey myKey = TestKeys.privateKey(3);

        Client client = null;
        try {
            client = new Client(myKey, main.myInfo, null);
        } catch (Exception e) {
            System.out.println("prepareClient exception: " + e.toString());
        }
        System.out.println("---------- verbose nothing ---------------");

        assertEquals (DatagramAdapter.VerboseLevel.NOTHING, main.network.getVerboseLevel());
        assertEquals (DatagramAdapter.VerboseLevel.NOTHING, main.node.getVerboseLevel());

        Contract testContract = new Contract(myKey);
        testContract.seal();
        assertTrue(testContract.isOk());
        Parcel parcel = createParcelWithFreshTU(client, testContract,Do.listOf(myKey));
        client.registerParcel(parcel.pack(), 1000);
        ItemResult itemResult = client.getState(parcel.getPayloadContract().getId());

        main.setVerboseLevel(DatagramAdapter.VerboseLevel.BASE);
        System.out.println("---------- verbose base ---------------");

        Contract testContract2 = new Contract(myKey);
        testContract2.seal();
        assertTrue(testContract2.isOk());
        Parcel parcel2 = createParcelWithFreshTU(client, testContract2,Do.listOf(myKey));
        client.registerParcel(parcel2.pack(), 1000);
        ItemResult itemResult2 = client.getState(parcel2.getPayloadContract().getId());

        assertEquals (DatagramAdapter.VerboseLevel.BASE, main.network.getVerboseLevel());
        assertEquals (DatagramAdapter.VerboseLevel.BASE, main.node.getVerboseLevel());

        main.setVerboseLevel(DatagramAdapter.VerboseLevel.NOTHING);
        System.out.println("---------- verbose nothing ---------------");

        Contract testContract3 = new Contract(myKey);
        testContract3.seal();
        assertTrue(testContract3.isOk());
        Parcel parcel3 = createParcelWithFreshTU(client, testContract3,Do.listOf(myKey));
        client.registerParcel(parcel3.pack(), 1000);
        ItemResult itemResult3 = client.getState(parcel3.getPayloadContract().getId());

        assertEquals (DatagramAdapter.VerboseLevel.NOTHING, main.network.getVerboseLevel());
        assertEquals (DatagramAdapter.VerboseLevel.NOTHING, main.node.getVerboseLevel());

        mm.forEach(x -> x.shutdown());
    }

    @Test
    public void checkUDPVerbose() throws Exception {
        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            mm.add(createMain("node" + (i + 1), false));
        }

        Main main = mm.get(0);
        PrivateKey myKey = TestKeys.privateKey(3);

        Client client = null;
        try {
            client = new Client(myKey, main.myInfo, null);
        } catch (Exception e) {
            System.out.println("prepareClient exception: " + e.toString());
        }
        System.out.println("---------- verbose nothing ---------------");

        assertEquals (DatagramAdapter.VerboseLevel.NOTHING, main.network.getUDPVerboseLevel());

        Contract testContract = new Contract(myKey);
        testContract.seal();
        assertTrue(testContract.isOk());
        Parcel parcel = createParcelWithFreshTU(client, testContract,Do.listOf(myKey));
        client.registerParcel(parcel.pack(), 1000);
        ItemResult itemResult = client.getState(parcel.getPayloadContract().getId());

        main.setUDPVerboseLevel(DatagramAdapter.VerboseLevel.BASE);
        System.out.println("---------- verbose base ---------------");

        Contract testContract2 = new Contract(myKey);
        testContract2.seal();
        assertTrue(testContract2.isOk());
        Parcel parcel2 = createParcelWithFreshTU(client, testContract2,Do.listOf(myKey));
        client.registerParcel(parcel2.pack(), 1000);
        ItemResult itemResult2 = client.getState(parcel2.getPayloadContract().getId());

        assertEquals (DatagramAdapter.VerboseLevel.BASE, main.network.getUDPVerboseLevel());

        main.setUDPVerboseLevel(DatagramAdapter.VerboseLevel.NOTHING);

        main.setUDPVerboseLevel(DatagramAdapter.VerboseLevel.DETAILED);
        System.out.println("---------- verbose detailed ---------------");

        Contract testContract4 = new Contract(myKey);
        testContract4.seal();
        assertTrue(testContract4.isOk());
        Parcel parcel4 = createParcelWithFreshTU(client, testContract4,Do.listOf(myKey));
        client.registerParcel(parcel4.pack(), 1000);
        ItemResult itemResult4 = client.getState(parcel4.getPayloadContract().getId());

        assertEquals (DatagramAdapter.VerboseLevel.DETAILED, main.network.getUDPVerboseLevel());

        main.setUDPVerboseLevel(DatagramAdapter.VerboseLevel.NOTHING);
        System.out.println("---------- verbose nothing ---------------");

        Contract testContract3 = new Contract(myKey);
        testContract3.seal();
        assertTrue(testContract3.isOk());
        Parcel parcel3 = createParcelWithFreshTU(client, testContract3,Do.listOf(myKey));
        client.registerParcel(parcel3.pack(), 1000);
        ItemResult itemResult3 = client.getState(parcel3.getPayloadContract().getId());

        assertEquals (DatagramAdapter.VerboseLevel.NOTHING, main.network.getUDPVerboseLevel());

        mm.forEach(x -> x.shutdown());
    }

    @Test
    public void checkShutdown() throws Exception {
        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            mm.add(createMain("node" + (i + 1), false));
        }

        Main main = mm.get(0);
        PrivateKey myKey = TestKeys.privateKey(3);

        Client client = null;
        try {
            client = new Client(myKey, main.myInfo, null);
        } catch (Exception e) {
            System.out.println("prepareClient exception: " + e.toString());
        }

        Contract testContract = new Contract(myKey);
        for (int i = 0; i < 10; i++) {
            Contract nc = new Contract(myKey);
            testContract.addNewItems(nc);
        }
        testContract.seal();
        assertTrue(testContract.isOk());
        Parcel parcel = createParcelWithFreshTU(client, testContract,Do.listOf(myKey));
        client.registerParcel(parcel.pack());
        System.out.println(">> before shutdown state: " + client.getState(parcel.getPayloadContract().getId()));
        System.out.println(">> before shutdown state: " + client.getState(parcel.getPayloadContract().getNew().get(0).getId()));
        main.shutdown();

        mm.remove(main);
        main = createMain("node1", false);
        mm.add(main);
        try {
            client = new Client(myKey, main.myInfo, null);
        } catch (Exception e) {
            System.out.println("prepareClient exception: " + e.toString());
        }
        ItemResult itemResult = client.getState(parcel.getPayloadContract().getId());
        ItemResult itemResult2 = client.getState(parcel.getPayloadContract().getNew().get(0).getId());
        System.out.println(">> after shutdown state: " + itemResult + " and new " + itemResult2);
        assertEquals (ItemState.UNDEFINED, itemResult.state);
        assertEquals (ItemState.UNDEFINED, itemResult2.state);

        mm.forEach(x -> x.shutdown());
    }

    @Test
    public void checkRestartUDP() throws Exception {
        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            mm.add(createMain("node" + (i + 1), false));
        }

        Main main = mm.get(0);
        PrivateKey myKey = TestKeys.privateKey(3);
        Client client = null;
        try {
            client = new Client(myKey, main.myInfo, null);
        } catch (Exception e) {
            System.out.println("prepareClient exception: " + e.toString());
        }

        Contract testContract = new Contract(myKey);
        for (int i = 0; i < 10; i++) {
            Contract nc = new Contract(myKey);
            testContract.addNewItems(nc);
        }
        testContract.seal();
        assertTrue(testContract.isOk());
        Parcel parcel = createParcelWithFreshTU(client, testContract,Do.listOf(myKey));
        client.registerParcel(parcel.pack());
        System.out.println(">> before restart state: " + client.getState(parcel.getPayloadContract().getId()));
        System.out.println(">> before restart state: " + client.getState(parcel.getPayloadContract().getNew().get(0).getId()));

        main.restartUDPAdapter();
        ItemResult itemResult = client.getState(parcel.getPayloadContract().getId());
        ItemResult itemResult2 = client.getState(parcel.getPayloadContract().getNew().get(0).getId());
        System.out.println(">> after restart state: " + itemResult + " and new " + itemResult2);

        while (itemResult.state.isPending()) {
            Thread.currentThread().sleep(100);
            itemResult = client.getState(parcel.getPayloadContract().getId());
            System.out.println(">> wait result: " + itemResult);
        }
        itemResult2 = client.getState(parcel.getPayloadContract().getNew().get(0).getId());

        assertEquals (ItemState.APPROVED, itemResult.state);
        assertEquals (ItemState.APPROVED, itemResult2.state);

        mm.forEach(x -> x.shutdown());
    }


    public synchronized Parcel createParcelWithFreshTU(Client client, Contract c, Collection<PrivateKey> keys) throws Exception {
        Set<PublicKey> ownerKeys = new HashSet();
        keys.stream().forEach(key->ownerKeys.add(key.getPublicKey()));
        Contract stepaTU = InnerContractsService.createFreshTU(100000000, ownerKeys);
        stepaTU.check();
        //stepaTU.setIsTU(true);
        stepaTU.traceErrors();
        ItemResult itemResult = client.register(stepaTU.getPackedTransaction(), 5000);
//        node.registerItem(stepaTU);
//        ItemResult itemResult = node.waitItem(stepaTU.getId(), 18000);
        assertEquals(ItemState.APPROVED, itemResult.state);
        Set<PrivateKey> keySet = new HashSet<>();
        keySet.addAll(keys);
        return ContractsService.createParcel(c, stepaTU, 150, keySet);
    }


    public static long idealConcurrentWork() {
        long s = 0l;
        for (int i = 0; i < 100000000; ++i)
            s += i;
        return s;
    }


    public void testSomeWork(Runnable someWork) throws Exception {
        final long THREADS_COUNT_MAX = Runtime.getRuntime().availableProcessors();

        System.out.println("warm up...");
        Thread thread0 = new Thread(someWork);
        thread0.start();
        thread0.join();

        long t1 = new Date().getTime();
        Thread thread1 = new Thread(someWork);
        thread1.start();
        thread1.join();
        long t2 = new Date().getTime();
        long singleTime = t2 - t1;
        System.out.println("single: " + singleTime + "ms");

        for (int THREADS_COUNT = 2; THREADS_COUNT <= THREADS_COUNT_MAX; ++THREADS_COUNT) {
            t1 = new Date().getTime();
            List<Thread> threadList = new ArrayList<>();
            for (int n = 0; n < THREADS_COUNT; ++n) {
                Thread thread = new Thread(someWork);
                threadList.add(thread);
                thread.start();
            }
            for (Thread thread : threadList)
                thread.join();
            t2 = new Date().getTime();
            long multiTime = t2 - t1;
            double boostRate = (double) THREADS_COUNT / (double) multiTime * (double) singleTime;
            System.out.println("multi(N=" + THREADS_COUNT + "): " + multiTime + "ms,   boostRate: x" + String.format("%.2f", boostRate));
        }
    }


    @Test
    public void testBossPack() throws Exception {
        byte[] br = new byte[200];
        new Random().nextBytes(br);
        Runnable r = () -> {
            try {
                Boss.Writer w = new Boss.Writer();
                for (int i = 0; i < 1000000; ++i) {
                    w.writeObject(br);
                    br[0]++;
                }
                w.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        };
        testSomeWork(() -> {
            r.run();
        });
        Thread.sleep(1500);
        testSomeWork(() -> {
            r.run();
        });
    }


    @Test
    public void testContractCheck() throws Exception {
        PrivateKey key = TestKeys.privateKey(3);
        testSomeWork(() ->  {
            try {
                Contract c = new Contract(key);
                for (int k = 0; k < 500; k++) {
                    Contract nc = new Contract(key);
                    nc.seal();
                    c.addNewItems(nc);
                }
                c.seal();
                c.check();
            } catch (Quantiser.QuantiserException e) {
                e.printStackTrace();
            }
        });
    }



    @Test
    public void testTransactionUnpack() throws Exception {
        testSomeWork(() ->  {
            try {
                PrivateKey key = TestKeys.privateKey(3);
                Contract contract = createContract500(key);
                contract.seal();
                byte[] bytes = contract.getPackedTransaction();
                for (int i = 0; i < 20; ++i)
                    TransactionPack.unpack(bytes);
            } catch (Exception e) {
                System.out.println("exception: " + e.toString());
            }
        });
    }




    @Test
    public void testLedger() throws Exception {

        Properties properties = new Properties();

        File file = new File("./src/test_config_2/" + "config/config.yaml");
        if (file.exists())
            properties.load(new FileReader(file));

        final PostgresLedger ledger_s = new PostgresLedger(PostgresLedgerTest.CONNECTION_STRING, properties);
        StateRecord record = ledger_s.findOrCreate(HashId.createRandom());

        System.out.println("--- find or create ---");
        testSomeWork(() ->  {
            for (int i = 0; i < 10000; ++i)
                ledger_s.findOrCreate(HashId.createRandom());
        });

        System.out.println("--- lock to create ---");
        testSomeWork(() ->  {
            for (int i = 0; i < 10000; ++i)
                record.createOutputLockRecord(HashId.createRandom());
        });

        System.out.println("--- lock to revoke ---");
        testSomeWork(() ->  {
            for (int i = 0; i < 10000; ++i)
                record.lockToRevoke(HashId.createRandom());
        });
    }



    @Test
    public void testIdealConcurrentWork() throws Exception {
        testSomeWork(() -> {
            for (int i = 0; i < 100; ++i)
                idealConcurrentWork();
        });
    }


    @Test
    public void testNewContractSeal() throws Exception {
        testSomeWork(() -> {
            for (int i = 0; i < 10; ++i) {
                PrivateKey myKey = null;
                try {
                    myKey = TestKeys.privateKey(3);
                } catch (Exception e) {
                }
                Contract testContract = new Contract(myKey);
                for (int iContract = 0; iContract < 10; ++iContract) {
                    Contract nc = new Contract(myKey);
                    nc.seal();
                    testContract.addNewItems(nc);
                }
                testContract.seal();
            }
        });
    }


    @Test
    public void testHashId() throws Exception {
        testSomeWork(() -> {
            byte[] randBytes = Do.randomBytes(1*1024*1024);
            for (int i = 0; i < 100; ++i)
                HashId.of(randBytes);
        });
    }



    @Test
    public void registerContract500_seal() throws Exception {
        TestSpace ts = prepareTestSpace();
        Contract contract = createContract500(ts.myKey);
        ItemResult itemResult = ts.client.register(contract.getLastSealedBinary(), 10000);
        assertEquals(ItemState.DECLINED, itemResult.state);
        int i = 0;
        for (Approvable sub : contract.getNewItems()) {
            ItemResult subItemResult = ts.client.getState(sub);
            System.out.println("" + (i++) + " - " + subItemResult.state);
            assertEquals(ItemState.UNDEFINED, subItemResult.state);
        }

        ts.nodes.forEach(n -> n.shutdown());
    }



    @Test
    public void registerContract500approved_seal() throws Exception {
        TestSpace ts = prepareTestSpace();
        Contract contract = createContract500(ts.myKey);
        int i = 0;
        for (Approvable sub : contract.getNewItems()) {
            Contract subContract = (Contract) sub;
            ItemResult subItemResult = ts.client.register(subContract.getLastSealedBinary(), 1000);
            assertEquals(ItemState.APPROVED, subItemResult.state);
            ++i;
            if (i % 10 == 0)
                System.out.println("register subContract: " + i);
        }
        ItemResult itemResult = ts.client.register(contract.getLastSealedBinary(), 10000);
        assertEquals(ItemState.DECLINED, itemResult.state);
        i = 0;
        for (Approvable sub : contract.getNewItems()) {
            ItemResult subItemResult = ts.client.getState(sub);
            System.out.println("" + (i++) + " - " + subItemResult.state);
            assertEquals(ItemState.APPROVED, subItemResult.state);
        }

        ts.nodes.forEach(n -> n.shutdown());
    }



    @Test
    public void registerContract500approvedHalf_seal() throws Exception {
        TestSpace ts = prepareTestSpace();
        Contract contract = createContract500(ts.myKey);
        int i = 0;
        for (Approvable sub : contract.getNewItems()) {
            ++i;
            Contract subContract = (Contract) sub;
            if (i % 2 == 0) {
                ItemResult subItemResult = ts.client.register(subContract.getLastSealedBinary(), 1000);
                assertEquals(ItemState.APPROVED, subItemResult.state);
            } else {
                ItemResult subItemResult = ts.client.getState(subContract.getId());
                assertEquals(ItemState.UNDEFINED, subItemResult.state);
            }
            if (i % 10 == 0)
                System.out.println("register subContract: " + i);
        }
        ItemResult itemResult = ts.client.register(contract.getLastSealedBinary(), 10000);
        assertEquals(ItemState.DECLINED, itemResult.state);
        i = 0;
        for (Approvable sub : contract.getNewItems()) {
            ++i;
            ItemResult subItemResult = ts.client.getState(sub);
            System.out.println("" + i + " - " + subItemResult.state);
            if (i % 2 == 0)
                assertEquals(ItemState.APPROVED, subItemResult.state);
            else
                assertEquals(ItemState.UNDEFINED, subItemResult.state);
        }

        ts.nodes.forEach(n -> n.shutdown());
    }



    @Test
    public void registerContract500_pack() throws Exception {
        TestSpace ts = prepareTestSpace();
        Contract contract = createContract500(ts.myKey);
        ItemResult itemResult = ts.client.register(contract.getPackedTransaction(), 30000);
        assertEquals(ItemState.APPROVED, itemResult.state);
        Thread.sleep(5000);
        int i = 0;
        for (Approvable sub : contract.getNewItems()) {
            ItemResult subItemResult = ts.client.getState(sub);
            System.out.println("" + (i++) + " - " + subItemResult.state);
            assertEquals(ItemState.APPROVED, subItemResult.state);
        }

        ts.nodes.forEach(n -> n.shutdown());
    }



    @Test
    public void registerContract500approved_pack() throws Exception {
        TestSpace ts = prepareTestSpace();
        Contract contract = createContract500(ts.myKey);
        int i = 0;
        for (Approvable sub : contract.getNewItems()) {
            Contract subContract = (Contract) sub;
            ItemResult subItemResult = ts.client.register(subContract.getLastSealedBinary(), 1000);
            assertEquals(ItemState.APPROVED, subItemResult.state);
            ++i;
            if (i % 10 == 0)
                System.out.println("register subContract: " + i);
        }
        System.out.println("register parent contract...");
        ItemResult itemResult = ts.client.register(contract.getPackedTransaction(), 30000);
        assertEquals(ItemState.DECLINED, itemResult.state);
        Thread.sleep(5000);
        i = 0;
        for (Approvable sub : contract.getNewItems()) {
            ItemResult subItemResult = ts.client.getState(sub);
            System.out.println("" + (i++) + " - " + subItemResult.state);
            assertEquals(ItemState.APPROVED, subItemResult.state);
        }

        ts.nodes.forEach(n -> n.shutdown());
    }



    @Test
    public void registerContract500approvedHalf_pack() throws Exception {
        TestSpace ts = prepareTestSpace();
        Contract contract = createContract500(ts.myKey);
        int i = 0;
        for (Approvable sub : contract.getNewItems()) {
            ++i;
            Contract subContract = (Contract) sub;
            if (i % 2 == 0) {
                ItemResult subItemResult = ts.client.register(subContract.getLastSealedBinary(), 1000);
                assertEquals(ItemState.APPROVED, subItemResult.state);
            } else {
                ItemResult subItemResult = ts.client.getState(subContract.getId());
                assertEquals(ItemState.UNDEFINED, subItemResult.state);
            }
            if (i % 10 == 0)
                System.out.println("register subContract: " + i);
        }
        System.out.println("register parent contract...");
        ItemResult itemResult = ts.client.register(contract.getPackedTransaction(), 30000);
        assertEquals(ItemState.DECLINED, itemResult.state);
        Thread.sleep(5000);
        i = 0;
        for (Approvable sub : contract.getNewItems()) {
            ++i;
            ItemResult subItemResult = ts.client.getState(sub);
            System.out.println("" + i + " - " + subItemResult.state);
            if (i % 2 == 0)
                assertEquals(ItemState.APPROVED, subItemResult.state);
            else
                assertEquals(ItemState.UNDEFINED, subItemResult.state);
        }

        ts.nodes.forEach(n -> n.shutdown());
    }



    @Test
    public void registerContractWithAnonymousId() throws Exception {
        TestSpace ts = prepareTestSpace();
        PrivateKey myPrivKey = TestKeys.privateKey(1);
        PublicKey myPubKey = myPrivKey.getPublicKey();
        byte[] myAnonId = myPrivKey.createAnonymousId();

        Contract contract = new Contract();
        contract.setExpiresAt(ZonedDateTime.now().plusDays(90));
        Role r = contract.setIssuerKeys(AnonymousId.fromBytes(myAnonId));
        contract.registerRole(new RoleLink("owner", "issuer"));
        contract.registerRole(new RoleLink("creator", "issuer"));
        contract.addPermission(new ChangeOwnerPermission(r));

        contract.addSignerKey(myPrivKey);
        contract.seal();

        System.out.println("contract.check(): " + contract.check());
        contract.traceErrors();

        //ItemResult itemResult = ts.client.register(contract.getPackedTransaction(), 5000);
        ItemResult itemResult0 = ts.node.node.registerItem(contract);
        //Thread.sleep(1000000000);
        ItemResult itemResult = ts.node.node.waitItem(contract.getId(), 100);
        assertEquals(ItemState.APPROVED, itemResult.state);
    }



    @Test
    public void registerContractWithAnonymousId_bak() throws Exception {
        TestSpace ts = prepareTestSpace();
        PrivateKey myPrivKey = TestKeys.privateKey(1);
        PublicKey myPubKey = myPrivKey.getPublicKey();
        byte[] myAnonId = myPrivKey.createAnonymousId();

        Contract contract = new Contract();
        contract.setExpiresAt(ZonedDateTime.now().plusDays(90));
        Role r = contract.setIssuerKeys(myPubKey);
        contract.registerRole(new RoleLink("owner", "issuer"));
        contract.registerRole(new RoleLink("creator", "issuer"));
        contract.addPermission(new ChangeOwnerPermission(r));

        contract.addSignerKey(myPrivKey);
        contract.seal();

        ItemResult itemResult = ts.client.register(contract.getPackedTransaction(), 5000);
        assertEquals(ItemState.APPROVED, itemResult.state);
    }


    private TestSpace prepareTestSpace() throws Exception {
        return prepareTestSpace(TestKeys.privateKey(3));
    }

    private TestSpace prepareTestSpace(PrivateKey key) throws Exception {
        TestSpace testSpace = new TestSpace();
        testSpace.nodes = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            testSpace.nodes.add(createMain("node" + (i + 1), false));
        testSpace.node = testSpace.nodes.get(0);
        assertEquals("http://localhost:8080", testSpace.node.myInfo.internalUrlString());
        assertEquals("http://localhost:8080", testSpace.node.myInfo.publicUrlString());
        testSpace.myKey = key;
        testSpace.client = new Client(testSpace.myKey, testSpace.node.myInfo, null);
        return testSpace;
    }



    private Contract createContract500(PrivateKey key) {
        Contract contract = new Contract(key);
        for (int i = 0; i < 500; ++i) {
            Contract sub = new Contract(key);
            sub.seal();
            contract.addNewItems(sub);
        }
        contract.seal();
        return contract;
    }



    private class TestSpace {
        public List<Main> nodes = null;
        public Main node = null;
        PrivateKey myKey = null;
        Client client = null;
    }


    private static final int MAX_PACKET_SIZE = 512;
    protected void sendBlock(UDPAdapter.Block block, DatagramSocket socket) throws InterruptedException {

        if(!block.isValidToSend()) {
            block.prepareToSend(MAX_PACKET_SIZE);
        }

        List<DatagramPacket> outs = new ArrayList(block.getDatagrams().values());

        try {

            for (DatagramPacket d : outs) {
                socket.send(d);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    protected void sendHello(NodeInfo myNodeInfo, NodeInfo destination, UDPAdapter udpAdapter, DatagramSocket socket) throws InterruptedException {

//        System.out.println(">> send froud from " + myNodeInfo.getNumber() + " to " + destination.getNumber());
        Binder binder = Binder.fromKeysValues(
                "data", myNodeInfo.getNumber()
        );
        UDPAdapter.Block block = udpAdapter.createTestBlock(myNodeInfo.getNumber(), destination.getNumber(),
                new Random().nextInt(Integer.MAX_VALUE), UDPAdapter.PacketTypes.HELLO,
                destination.getNodeAddress().getAddress(), destination.getNodeAddress().getPort(),
                Boss.pack(binder));
        sendBlock(block, socket);
    }


    @Test
    public void udpDisruptionTest() throws Exception{
        List<Main> mm = new ArrayList<>();
        final int NODE_COUNT = 4;
        final int PORT_BASE = 12000;

        for (int i = 0; i < NODE_COUNT; i++) {
            mm.add(createMain("node" + (i + 1), false));
        }
//        mm.get(0).setUDPVerboseLevel(DatagramAdapter.VerboseLevel.BASE);
//        mm.get(1).setUDPVerboseLevel(DatagramAdapter.VerboseLevel.DETAILED);

        class TestRunnable implements Runnable {

            int finalI;
            int finalJ;
            boolean alive = true;

            @Override
            public void run() {
                try {
                    NodeInfo source = mm.get(finalI).myInfo;
                    NodeInfo destination = mm.get(finalJ).myInfo;
                    DatagramSocket socket = new DatagramSocket(PORT_BASE+ finalI*NODE_COUNT+finalJ);

                    while (alive) {
                        sendHello(source,destination,mm.get(finalI).network.getUDPAdapter(),socket);
                    }
                } catch (Exception e) {
                    System.out.println("runnable exception: " + e.toString());
                }
            }
        }

        List<Thread> threadsList = new ArrayList<>();
        List<TestRunnable> runnableList = new ArrayList<>();
        for(int i = 0; i < NODE_COUNT; i++) {
            for(int j = 0; j < NODE_COUNT;j++) {
                if(j == i)
                    continue;
                final int finalI = i;
                final int finalJ = j;
                TestRunnable runnableSingle = new TestRunnable();
                runnableList.add(runnableSingle);
                threadsList.add(
                new Thread(() -> {
                    runnableSingle.finalI = finalI;
                    runnableSingle.finalJ = finalJ;
                    runnableSingle.run();

                }));
            }
        }

        for (Thread th : threadsList) {
            th.start();
        }
        Thread.sleep(1000);

        PrivateKey myKey = TestKeys.privateKey(0);
        Client client = new Client(myKey,mm.get(0).myInfo,null);
        Contract contract = new Contract(myKey);
        contract.seal();
        Parcel parcel = createParcelWithFreshTU(client,contract,Do.listOf(myKey));
        client.registerParcel(parcel.pack(),60000);
        ItemResult rr;
        while(true) {
            rr = client.getState(contract.getId());
            if(!rr.state.isPending())
                break;
        }

        assertEquals(rr.state, ItemState.APPROVED);



        for (TestRunnable tr : runnableList) {
            tr.alive = false;
        }
        for (Thread th : threadsList) {
            th.interrupt();
        }
        mm.forEach(x -> x.shutdown());
    }




    @Test
    public void dbSanitationTest() throws Exception {
        final int NODE_COUNT = 4;
        PrivateKey myKey = TestKeys.privateKey(NODE_COUNT);


        List<String> dbUrls = new ArrayList<>();
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t1");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t2");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t3");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t4");
        List<Ledger> ledgers = new ArrayList<>();
        dbUrls.stream().forEach(url -> {
            try {
//                clearLedger(url);
                PostgresLedger ledger = new PostgresLedger(url);
                ledgers.add(ledger);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        Random random = new Random(123);

        List<Contract> origins = new ArrayList<>();
        List<Contract> newRevisions = new ArrayList<>();
        List<Contract> newContracts = new ArrayList<>();

        final int N = 100;
        for(int i = 0; i < N; i++) {
            Contract origin = new Contract(myKey);
            origin.seal();
            origins.add(origin);

            Contract newRevision = origin.createRevision(myKey);

            if(i < N/2) {
                //ACCEPTED
                newRevision.setOwnerKeys(TestKeys.privateKey(NODE_COUNT + 1).getPublicKey());
            } else {
                //DECLINED
                //State is equal
            }

            Contract newContract = new Contract(myKey);
            newRevision.addNewItems(newContract);
            newRevision.seal();

            newContracts.add(newContract);
            newRevisions.add(newRevision);
            int unfinishedNodesCount = random.nextInt(2)+1;
            Set<Integer> unfinishedNodesNumbers = new HashSet<>();
            while(unfinishedNodesCount > unfinishedNodesNumbers.size()) {
                unfinishedNodesNumbers.add(random.nextInt(NODE_COUNT)+1);
            }

            System.out.println("item# "+ newRevision.getId().toBase64String().substring(0,6) + " nodes " + unfinishedNodesNumbers.toString());
            int finalI = i;
            for(int j = 0; j < NODE_COUNT;j++) {
                boolean finished = !unfinishedNodesNumbers.contains(j+1);
                Ledger ledger = ledgers.get(j);


                StateRecord originRecord = ledger.findOrCreate(origin.getId());
                originRecord.setExpiresAt(origin.getExpiresAt());
                originRecord.setCreatedAt(origin.getCreatedAt());

                StateRecord newRevisionRecord = ledger.findOrCreate(newRevision.getId());
                newRevisionRecord.setExpiresAt(newRevision.getExpiresAt());
                newRevisionRecord.setCreatedAt(newRevision.getCreatedAt());

                StateRecord newContractRecord = ledger.findOrCreate(newContract.getId());
                newContractRecord.setExpiresAt(newContract.getExpiresAt());
                newContractRecord.setCreatedAt(newContract.getCreatedAt());

                if(finished) {
                    if(finalI < N/2) {
                        originRecord.setState(ItemState.REVOKED);
                        newContractRecord.setState(ItemState.APPROVED);
                        newRevisionRecord.setState(ItemState.APPROVED);
                    } else {
                        originRecord.setState(ItemState.APPROVED);
                        newContractRecord.setState(ItemState.UNDEFINED);
                        newRevisionRecord.setState(ItemState.DECLINED);
                    }
                } else {
                    originRecord.setState(ItemState.LOCKED);
                    originRecord.setLockedByRecordId(newRevisionRecord.getRecordId());
                    newContractRecord.setState(ItemState.LOCKED_FOR_CREATION);
                    newContractRecord.setLockedByRecordId(newRevisionRecord.getRecordId());
                    newRevisionRecord.setState(finalI < N/2 ? ItemState.PENDING_POSITIVE : ItemState.PENDING_NEGATIVE);
                }

                originRecord.save();
                ledger.putItem(originRecord,origin, Instant.now().plusSeconds(3600*24));
                newRevisionRecord.save();
                ledger.putItem(newRevisionRecord,newRevision, Instant.now().plusSeconds(3600*24));
                if(newContractRecord.getState() == ItemState.UNDEFINED) {
                    newContractRecord.destroy();
                } else {
                    newContractRecord.save();
                }

            }
        }
        ledgers.stream().forEach(ledger -> ledger.close());
        ledgers.clear();

        List<Main> mm = new ArrayList<>();
        List<Client> clients = new ArrayList<>();

        for (int i = 0; i < NODE_COUNT; i++) {
            Main m = createMain("node" + (i + 1), false);
            mm.add(m);
            Client client = new Client(TestKeys.privateKey(i), m.myInfo, null);
            clients.add(client);
        }






        while (true) {
            try {
                for(int i =0; i < NODE_COUNT; i++) {
                    clients.get(i).getState(newRevisions.get(0));
                }
                break;
            } catch (ClientError e) {
                Thread.sleep(1000);
                mm.stream().forEach( m -> System.out.println("node#" +m.myInfo.getNumber() + " is " +  (m.node.isSanitating() ? "" : "not ") + "sanitating"));
            }

        }

        Contract contract = new Contract(TestKeys.privateKey(3));
        contract.seal();
        ItemResult ir = clients.get(0).register(contract.getPackedTransaction(), 10000);
        ir.errors.toString();


        for(int i = 0; i < N; i++) {
            ItemResult rr = clients.get(i%NODE_COUNT).getState(newRevisions.get(i).getId());
            ItemState targetState = i < N/2 ? ItemState.APPROVED : ItemState.DECLINED;
            assertEquals(rr.state,targetState);
        }
        Thread.sleep(1000);
        mm.stream().forEach(m -> m.shutdown());
        Thread.sleep(1000);

        dbUrls.stream().forEach(url -> {
            try {
                PostgresLedger ledger = new PostgresLedger(url);
                assertTrue(ledger.findUnfinished().isEmpty());
                ledger.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }


    private void clearLedger(String url) throws Exception {
        Properties properties = new Properties();
        try(DbPool dbPool = new DbPool(url, properties, 64)) {
            try (PooledDb db = dbPool.db()) {
                try (PreparedStatement statement = db.statement("delete from items;")
                ) {
                    statement.executeUpdate();
                }

                try (PreparedStatement statement = db.statement("delete from ledger;")
                ) {
                    statement.executeUpdate();
                }
            }
        }
    }

    @Test
    public void nodeStatsTest() throws Exception {
        PrivateKey issuerKey = new PrivateKey(Do.read("./src/test_contracts/keys/reconfig_key.private.unikey"));
        TestSpace testSpace = prepareTestSpace(issuerKey);
        testSpace.nodes.get(0).config.setStatsIntervalSmall(Duration.ofSeconds(4));
        testSpace.nodes.get(0).config.setStatsIntervalBig(Duration.ofSeconds(60));
        testSpace.nodes.get(0).config.getKeysWhiteList().add(issuerKey.getPublicKey());

        Binder binder = testSpace.client.getStats();
        System.out.println(binder.toString());
        Instant now;
        for (int i = 0; i < 100; i++) {
            now = Instant.now();
            Contract contract = new Contract(issuerKey);
            contract.seal();
            testSpace.client.register(contract.getPackedTransaction(),1500);
            contract = new Contract(issuerKey);
            contract.seal();
            testSpace.client.register(contract.getPackedTransaction(),1500);

            Thread.sleep(4000-(Instant.now().toEpochMilli()-now.toEpochMilli()));
            binder = testSpace.client.getStats();
            System.out.println(binder.toString());
        }

    }
}