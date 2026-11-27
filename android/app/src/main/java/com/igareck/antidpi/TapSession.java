/* Adapted from sigmabosshvh/android-testtap-egress, MIT License.
 * Copyright (c) 2026 sigmabosshvh. Modifications (c) 2026 Igor Krauch.
 * See assets/licenses/TestTap-MIT.txt and SOURCE_LOCK.json. */
package com.igareck.antidpi;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import java.io.FileDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import android.os.Binder;
import android.content.ContextWrapper;
import android.os.IBinder;

import java.lang.reflect.Constructor;
import java.util.function.Supplier;

import java.util.Arrays;

import android.system.ErrnoException;

import java.io.BufferedReader;
import java.io.FileReader;
import java.math.BigInteger;
import java.io.IOException;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.json.JSONArray;


final class TapSession {
    interface Observer { void checkpoint(String tap,boolean visibilityRequested)throws Exception; }
    interface InterfaceInventory { String[] names()throws Exception; }
    private InterfaceInventory inventory=TapSession::interfaceNames;
    private Observer observer;
    TapSession(Context context,Observer observer){this(context);this.observer=observer;}
    TapSession(Context context,Observer observer,InterfaceInventory inventory){this(context,observer);this.inventory=inventory;}
    private void checkpoint(boolean visibility)throws Exception{if(observer!=null)observer.checkpoint(tapName==null?"":tapName,visibility);}
    static void restoreTestInterfaceVisibility(Context context)throws Exception {
        Object manager=context.getSystemService("ethernet");
        if(manager==null)throw new IOException("EthernetManager unavailable during recovery");
        manager.getClass().getMethod("setIncludeTestInterfaces",boolean.class).invoke(manager,false);
    }

    private static final class ShellOpContext extends ContextWrapper {
        ShellOpContext(Context base) {
            super(base);
        }

        public String getOpPackageName() {
            return "com.android.shell";
        }
    }

    private final AtomicBoolean busy=new AtomicBoolean();
    private final Object reportLock=new Object();
    private JSONObject report=new JSONObject();
    private JSONArray steps=new JSONArray();
    private volatile boolean cancelled;
    private volatile long deadline;
    private long began;
    private boolean tetherRequested, testInterfacesIncluded;
    private final int ownerUid;
    private long tcpTimestamp;
    private int discovers, requests, frames, dhcpPackets;
    private static class SetupTimeout extends IOException {
        SetupTimeout(String message){super(message);}
    }
    private static final class DhcpTimeout extends SetupTimeout {
        DhcpTimeout(String phase){super("DHCP "+phase+" timeout: локальный TAP не получил ответ Android; проверка сайта ещё не началась");}
    }

    private void enforceCaller() {
        int uid=Binder.getCallingUid();
        if(uid!=ownerUid&&uid!=2000&&uid!=0)throw new SecurityException("Caller is not this app or ADB: caller_uid="+uid+"; app_uid="+ownerUid+"; app="+AppIdentity.PACKAGE_NAME);
    }
    private void put(String key,Object value) {
        synchronized(reportLock){try{report.put(key,value);}catch(Exception e){throw new IllegalStateException(e);}}
    }
    private void step(String name)throws IOException {
        checkCancelled();
        synchronized(reportLock){try{
            report.put("stage",name);steps.put(new JSONObject().put("stage",name).put("elapsed_ms",SystemClock.elapsedRealtime()-began));
        }catch(Exception e){throw new IllegalStateException(e);}}
    }
    private void checkCancelled()throws IOException {
        if(cancelled)throw new IOException("Cancelled by user");
        if(SystemClock.elapsedRealtime()>deadline)throw new IOException("TAP setup exceeded its 45 second deadline");
    }
    private void await(CountDownLatch latch,int milliseconds,String error)throws Exception {
        long until=SystemClock.elapsedRealtime()+milliseconds;
        while(!latch.await(100,TimeUnit.MILLISECONDS)){
            checkCancelled();if(SystemClock.elapsedRealtime()>=until)throw new SetupTimeout(error+"; Android не подтвердил подготовку локального TAP, проверка сайта ещё не началась");
        }
        checkCancelled();
    }
    public String progress(){enforceCaller();synchronized(reportLock){return report.toString();}}
    public void cancel(){enforceCaller();cancelled=true;}
    private void refuseExistingEthernetOrTap()throws Exception {
        for(String name:inventory.names()){
            if(name.startsWith("testtap")||name.startsWith("testtun")||name.matches("eth[0-9]+"))
                throw new IOException("Existing Ethernet/test interface "+name+"; probe will not change its state");
        }
    }
    private static String[] interfaceNames()throws Exception {
        java.util.ArrayList<String> names=new java.util.ArrayList<>();
        Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
        while(interfaces!=null&&interfaces.hasMoreElements())names.add(interfaces.nextElement().getName());
        return names.toArray(new String[0]);
    }
    private boolean interfaceExists(String name)throws Exception {for(String n:inventory.names())if(name.equals(n))return true;return false;}
    private static byte[] parseIpv4(String value) {
        String[] octets=value.split("\\.",-1);
        if(octets.length!=4)throw new IllegalArgumentException("Enter one IPv4 address, not a hostname");
        byte[] ip=new byte[4];
        for(int i=0;i<4;i++){
            if(!octets[i].matches("[0-9]{1,3}"))throw new IllegalArgumentException("Invalid IPv4");
            int n=Integer.parseInt(octets[i]);if(n>255)throw new IllegalArgumentException("Invalid IPv4");ip[i]=(byte)n;
        }
        int first=ip[0]&255;
        if(first==0||first==127||first>=224)throw new IllegalArgumentException("Use a unicast Internet IPv4 address");
        return ip;
    }
    public void open() throws Exception {
        if(!busy.compareAndSet(false,true))throw new IOException("TAP setup already running");
        began=SystemClock.elapsedRealtime();deadline=began+45000;
        synchronized(reportLock){report=new JSONObject();steps=new JSONArray();}
        try {
            checkCancelled();
            put("version",AppLog.VERSION);put("status","STARTING");put("steps",steps);
            put("service_uid",Process.myUid());put("app_uid",ownerUid);put("application_id",AppIdentity.PACKAGE_NAME);put("uses_vpn_service",false);
            try(BufferedReader caps=new BufferedReader(new FileReader("/proc/self/status"))){String line;
                while((line=caps.readLine())!=null)if(line.startsWith("CapEff:")){put("cap_eff_hex",line.substring(7).trim());break;}
            }
            if(Process.myUid()!=2000)throw new IOException("Нужна активированный сетевой модуль: UID 2000.");
            JSONArray attempts=new JSONArray();put("setup_attempts",attempts);
            for(int attempt=1;;attempt++){
                put("setup_attempt",attempt);discovers=0;requests=0;frames=0;dhcpPackets=0;
                try{
                    refuseExistingEthernetOrTap();
                    step("CREATE_TAP");createTap();put("tap",tapName);checkpoint(false);
                    step("ETHERNET_SERVER");enableEthernetForTap();
                    step("START_TETHERING");startEthernetTethering();
                    step("CHECK_OWN_TETHERING");recordTetheringState();
                    step("DHCP_AND_ARP");provisionClient();
                    attempts.put(attemptReport(attempt,"ready"));break;
                }catch(SetupTimeout e){
                    attempts.put(attemptReport(attempt,e.getMessage()));String previous=tapName;
                    cleanupAll();boolean clean;
                    synchronized(reportLock){clean=report.optBoolean("cleanup_ok",false);}
                    if(attempt>=2||!clean||cancelled||deadline-SystemClock.elapsedRealtime()<16000)throw e;
                    step("RETRY_AFTER_OWN_CLEANUP");
                    long until=SystemClock.elapsedRealtime()+2000;
                    while(previous!=null&&interfaceExists(previous)){
                        checkCancelled();if(SystemClock.elapsedRealtime()>=until)throw new IOException("Previous own TAP is still present; retry cancelled",e);Thread.sleep(100);
                    }
                    // Framework teardown is asynchronous. Recreate only after closing our
                    // descriptor and releasing our request; never reset physical Ethernet.
                    Thread.sleep(300);checkCancelled();
                }
            }
            put("client_ipv4",PacketBuilder.ipv4(clientIpv4));put("gateway_ipv4",PacketBuilder.ipv4(gatewayIpv4));
            put("dns_ipv4",PacketBuilder.ipv4(dnsIpv4));put("status","TAP_READY");
            deadline=Long.MAX_VALUE;
        } catch(Exception e){cleanupAll();throw e;} finally {busy.set(false);}
    }
    boolean reusable(){
        if(cancelled||tapFd==null||tapName==null||!tapFd.getFileDescriptor().valid())return false;
        synchronized(reportLock){if(!"TAP_READY".equals(report.optString("status")))return false;}
        try{return interfaceExists(tapName);}catch(Exception unavailable){return false;}
    }
    public FileDescriptor descriptor(){return tapFd.getFileDescriptor();}
    public byte[] configuration(){
        byte[] b=new byte[34];byte[] magic={'I','G','T','A','P','4',0,1};
        System.arraycopy(magic,0,b,0,8);System.arraycopy(clientIpv4,0,b,8,4);
        System.arraycopy(gatewayIpv4,0,b,12,4);System.arraycopy(dnsIpv4,0,b,16,4);
        System.arraycopy(CLIENT_MAC,0,b,20,6);System.arraycopy(gatewayMac,0,b,26,6);
        b[32]=5;b[33]=120; // MTU 1400; lwIP MSS 1360.
        return b;
    }
    public void close(){cleanupAll();put("status","CLOSED");}

    @SuppressLint("PrivateApi")
    private Object createShellTetheringManager() throws Exception {
        Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) serviceManagerClass.getMethod("getService", String.class).invoke(null, "tethering");

        if (binder == null) {
            throw new IllegalStateException("tethering service unavailable");
        }

        Class<?> tetheringManagerClass = Class.forName("android.net.TetheringManager");
        Constructor<?> constructor = tetheringManagerClass.getConstructor(Context.class, Supplier.class);

        return constructor.newInstance(new ShellOpContext(context), (Supplier<IBinder>) () -> binder);
    }

    // TetheringManager.TETHERING_ETHERNET
    private static final int TETHERING_ETHERNET = 5;

    private final Context context;

    private ParcelFileDescriptor tapFd;
    private String tapName;

    private Object ethernetManager;
    private Object tetheredInterfaceRequest;
    private Object tetheredInterfaceCallback;
    private Object tetheringManager;

    private byte[] clientIpv4;
    private byte[] gatewayIpv4;
    private byte[] gatewayMac;
    private byte[] dnsIpv4;

    private static final byte[] CLIENT_MAC = {
            0x02, 0x11, 0x22, 0x33, 0x44, 0x55
    };

    private byte[] TCP_TEST_IP = {
            1, 1, 1, 1
    };

    private int TCP_TEST_PORT = 443;
    private int TCP_SOURCE_PORT = 40000;
    private static final long TCP_INITIAL_SEQ = 0x12345678L;

    /*
     * Shizuku v13+.
     */
    public TapSession(Context context) {
        this.context = context;
        ownerUid=AppIdentity.resolve(context).uid;
    }

    /*
     * ============================================================
     * CREATE TAP
     * ============================================================
     */

    @SuppressWarnings("WrongConstant")
    private void createTap() throws Exception {
        Object testNetworkManager = context.getSystemService("test_network");
        if (testNetworkManager == null) {
            throw new IllegalStateException("test_network unavailable");
        }

        Object tap = testNetworkManager.getClass().getMethod("createTapInterface").invoke(testNetworkManager);

        tapName = (String) tap.getClass().getMethod("getInterfaceName").invoke(tap);
        tapFd = (ParcelFileDescriptor) tap.getClass().getMethod("getFileDescriptor").invoke(tap);

        if(tapName==null||!tapName.startsWith("testtap"))throw new IOException("Unexpected test TAP interface name");
        if (tapFd == null || !tapFd.getFileDescriptor().valid()) {
            throw new IllegalStateException("invalid TAP fd");
        }
    }

    private boolean isTapOpen() {

        return tapFd != null
                && tapFd.getFileDescriptor() != null
                && tapFd.getFileDescriptor().valid();
    }

    /*
     * ============================================================
     * ENABLE ETHERNET SERVER MODE
     * ============================================================
     */

    @SuppressWarnings("WrongConstant")
    @SuppressLint("PrivateApi")
    private void enableEthernetForTap() throws Exception {
        if (tapName == null || tapFd == null) {
            throw new IllegalStateException("TAP is not ready");
        }

        long identity = Binder.clearCallingIdentity();

        try {
            ethernetManager = context.getSystemService("ethernet");
            if (ethernetManager == null) {
                throw new IllegalStateException("EthernetManager unavailable");
            }

            checkpoint(true);testInterfacesIncluded=true;
            ethernetManager.getClass().getMethod("setIncludeTestInterfaces", boolean.class).invoke(ethernetManager, true);

            Class<?> callbackClass = Class.forName("android.net.EthernetManager$TetheredInterfaceCallback");

            CountDownLatch latch = new CountDownLatch(1);
            String[] availableIface = new String[1];

            tetheredInterfaceCallback = Proxy.newProxyInstance(
                    TapSession.class.getClassLoader(),
                    new Class<?>[]{callbackClass},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "onAvailable":
                                availableIface[0] = (String) args[0];
                                latch.countDown();
                                return null;

                            case "onUnavailable":
                                latch.countDown();
                                return null;

                            default:
                                return handleProxyObjectMethod(proxy, method, args);
                        }
                    });

            tetheredInterfaceRequest = ethernetManager.getClass()
                    .getMethod("requestTetheredInterface", Executor.class, callbackClass)
                    .invoke(ethernetManager, (Executor) Runnable::run, tetheredInterfaceCallback);

            await(latch,5000,"Timed out waiting for tethered interface");

            if (!tapName.equals(availableIface[0])) {
                throw new IllegalStateException("Expected " + tapName + ", got " + availableIface[0]);
            }

        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }


    @SuppressWarnings("WrongConstant")
    @SuppressLint("PrivateApi")
    private void startEthernetTethering() throws Exception {
        if (tetheredInterfaceRequest == null) {
            throw new IllegalStateException("Tethered Ethernet interface is not ready");
        }

        Class<?> builderClass = Class.forName("android.net.TetheringManager$TetheringRequest$Builder");
        Class<?> requestClass = Class.forName("android.net.TetheringManager$TetheringRequest");
        Class<?> callbackClass = Class.forName("android.net.TetheringManager$StartTetheringCallback");

        Object builder = builderClass.getConstructor(int.class).newInstance(TETHERING_ETHERNET);
        builderClass.getMethod("setShouldShowEntitlementUi", boolean.class).invoke(builder, false);
        Object request = builderClass.getMethod("build").invoke(builder);

        CountDownLatch latch = new CountDownLatch(1);
        int[] result = {-1};

        Object callback = Proxy.newProxyInstance(
                TapSession.class.getClassLoader(),
                new Class<?>[]{callbackClass},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "onTetheringStarted":
                            result[0] = 0;
                            latch.countDown();
                            return null;

                        case "onTetheringFailed":
                            result[0] = (Integer) args[0];
                            latch.countDown();
                            return null;

                        default:
                            return handleProxyObjectMethod(proxy, method, args);
                    }
                });

        long identity = Binder.clearCallingIdentity();

        try {
            tetheringManager = createShellTetheringManager();

            tetherRequested=true;
            tetheringManager.getClass()
                    .getMethod("startTethering", requestClass, Executor.class, callbackClass)
                    .invoke(tetheringManager, request, (Executor) Runnable::run, callback);

        } finally {
            Binder.restoreCallingIdentity(identity);
        }

        await(latch,15000,"Timed out waiting for tethering start");

        if (result[0] != 0) {
            throw new IllegalStateException("Ethernet tethering failed: " + result[0]);
        }
    }

    private void provisionClient() throws Exception {
        clientIpv4 = null;
        gatewayIpv4 = null;
        gatewayMac = null;

        int xid = (int) (System.nanoTime() ^ Process.myPid());

        PacketBuilder.DhcpInfo offer = null;

        long offerUntil=Math.min(deadline,SystemClock.elapsedRealtime()+12000);
        while(offer==null&&SystemClock.elapsedRealtime()<offerUntil){
            discovers++;put("dhcp_discovers",discovers);
            writeTapFrame(PacketBuilder.buildDhcpDiscover(CLIENT_MAC, xid));
            offer = waitForDhcp(xid,(int)Math.min(1000,offerUntil-SystemClock.elapsedRealtime()),PacketBuilder.DHCP_OFFER);
        }

        if (offer == null) {
            throw new DhcpTimeout("OFFER");
        }

        if (isZeroIpv4(offer.yiaddr)) {
            throw new IllegalStateException("DHCP OFFER has invalid yiaddr");
        }

        if (isZeroIpv4(offer.serverId)) {
            throw new IllegalStateException("DHCP OFFER has no server identifier");
        }

        byte[] request = PacketBuilder.buildDhcpRequest(CLIENT_MAC, xid, offer.yiaddr, offer.serverId);
        PacketBuilder.DhcpInfo ack=null;long ackUntil=Math.min(deadline,SystemClock.elapsedRealtime()+6000);
        while(ack==null&&SystemClock.elapsedRealtime()<ackUntil){
            requests++;put("dhcp_requests",requests);writeTapFrame(request);
            ack=waitForDhcp(xid,(int)Math.min(1500,ackUntil-SystemClock.elapsedRealtime()),PacketBuilder.DHCP_ACK,PacketBuilder.DHCP_NAK);
        }

        if (ack == null) {
            throw new DhcpTimeout("ACK");
        }

        if (ack.messageType == PacketBuilder.DHCP_NAK) {
            throw new IllegalStateException("DHCP NAK received");
        }

        byte[] clientIp = isZeroIpv4(ack.yiaddr) ? offer.yiaddr : ack.yiaddr;

        if (isZeroIpv4(ack.router)) {
            throw new IllegalStateException("DHCP ACK has no router");
        }

        clientIpv4 = Arrays.copyOf(clientIp, clientIp.length);
        gatewayIpv4 = Arrays.copyOf(ack.router, ack.router.length);
        dnsIpv4=ack.dns!=null?Arrays.copyOf(ack.dns,4):Arrays.copyOf(gatewayIpv4,4);

        byte[] arpRequest = PacketBuilder.buildArpRequest(CLIENT_MAC, clientIpv4, gatewayIpv4);
        writeTapFrame(arpRequest);

        gatewayMac = waitForArpReply();

        if (gatewayMac == null) {
            throw new IllegalStateException("ARP reply timeout");
        }
    }

    private void writeTapFrame(byte[] frame) throws Exception {
        checkCancelled();
        if (tapFd == null || !tapFd.getFileDescriptor().valid()) {
            throw new IllegalStateException("TAP FD is invalid");
        }

        FileDescriptor fd = tapFd.getFileDescriptor();
        int written = Os.write(fd, frame, 0, frame.length);

        if (written != frame.length) {
            throw new IllegalStateException("Short TAP write: " + written + "/" + frame.length);
        }
    }

    private byte[] readTapFrame(int timeoutMs) throws Exception {
        checkCancelled();
        if (tapFd == null || !tapFd.getFileDescriptor().valid()) {
            return null;
        }

        FileDescriptor fd = tapFd.getFileDescriptor();

        StructPollfd pollfd = new StructPollfd();
        pollfd.fd = fd;
        pollfd.events = (short) OsConstants.POLLIN;

        int ready = Os.poll(new StructPollfd[]{pollfd}, timeoutMs);

        if (ready <= 0 || (pollfd.revents & OsConstants.POLLIN) == 0) {
            return null;
        }

        byte[] buffer = new byte[4096];
        int length = Os.read(fd, buffer, 0, buffer.length);

        if(length<=0)return null;
        frames++;put("tap_frames_received",frames);
        byte[] frame=Arrays.copyOf(buffer,length);
        if(clientIpv4!=null){byte[] arp=PacketBuilder.replyToArpRequest(frame,CLIENT_MAC,clientIpv4);if(arp!=null)writeTapFrame(arp);}
        return frame;
    }

    private PacketBuilder.DhcpInfo waitForDhcp(int xid, int timeoutMs, int... wantedTypes) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;

        while (true) {
            long remaining = deadline - SystemClock.elapsedRealtime();

            if (remaining <= 0) {
                return null;
            }

            byte[] frame = readTapFrame((int) Math.min(remaining, 500));

            if (frame == null) {
                continue;
            }

            PacketBuilder.DhcpInfo info = PacketBuilder.parseDhcp(frame, xid, CLIENT_MAC);

            if (info == null) {
                continue;
            }
            dhcpPackets++;put("dhcp_packets_received",dhcpPackets);

            for (int wanted : wantedTypes) {
                if (info.messageType == wanted) {
                    return info;
                }
            }
        }
    }

    private byte[] waitForArpReply() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 3000;

        while (true) {
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) {
                return null;
            }

            byte[] frame = readTapFrame((int) Math.min(remaining, 500));
            if (frame == null) {
                continue;
            }

            byte[] mac = PacketBuilder.parseArpReplyMac(
                    frame, gatewayIpv4, CLIENT_MAC);

            if (mac != null) {
                return mac;
            }
        }
    }

    private static boolean isZeroIpv4(byte[] ip) {
        return ip == null || ip.length != 4 || (ip[0] == 0 && ip[1] == 0 && ip[2] == 0 && ip[3] == 0);
    }

    private JSONObject attemptReport(int attempt,String result)throws Exception {
        return new JSONObject().put("attempt",attempt).put("tap",tapName==null?"":tapName)
            .put("result",result).put("elapsed_ms",SystemClock.elapsedRealtime()-began)
            .put("discovers",discovers).put("requests",requests).put("frames",frames).put("matching_dhcp_packets",dhcpPackets);
    }
    private void recordTetheringState()throws IOException {
        checkCancelled();
        try{
            Method getter=tetheringManager.getClass().getMethod("getTetheredIfaces");
            Object value=getter.invoke(tetheringManager);
            if(value instanceof String[])for(String name:(String[])value)if(tapName.equals(name)){
                put("tether_state","own_interface_active");return;
            }
            put("tether_state","own_interface_not_reported; awaiting DHCP");
        }catch(ReflectiveOperationException|SecurityException e){
            // DHCP/ARP confirms readiness even on firmware whose optional
            // Ethernet inventory never reports the test interface.
            put("tether_state","query_unavailable; using DHCP readiness");
        }
    }

    /*
     * ============================================================
     * CLOSE TAP
     * ============================================================
     */

    /*
     * ============================================================
     * HELPERS
     * ============================================================
     */

    private static Object handleProxyObjectMethod(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        if ("toString".equals(name)) {
            return "TapSessionProxy";
        }
        if ("hashCode".equals(name)) {
            return System.identityHashCode(proxy);
        }
        if ("equals".equals(name)) {
            return args != null && args.length == 1 && proxy == args[0];
        }
        return null;
    }

    private static Throwable unwrap(Throwable e) {
        Throwable current = e;
        while (current instanceof InvocationTargetException
                        && ((InvocationTargetException) current).getTargetException() != null) {
            current = ((InvocationTargetException) current).getTargetException();
        }
        return current;
    }

    private interface Cleanup { void run()throws Throwable; }
    private boolean cleanupStep(JSONArray results,String name,Cleanup action) {
        try{action.run();results.put(new JSONObject().put("operation",name).put("ok",true));return true;}
        catch(Throwable e){try{results.put(new JSONObject().put("operation",name).put("ok",false).put("error",unwrap(e).toString()));}catch(Exception ignored){}return false;}
    }
    private synchronized void cleanupAll() {
        long identity=Binder.clearCallingIdentity();JSONArray results=new JSONArray();
        try {
            if(tetherRequested&&tetheringManager!=null&&
                cleanupStep(results,"stop_own_ethernet_tethering",()->tetheringManager.getClass().getMethod("stopTethering",int.class).invoke(tetheringManager,TETHERING_ETHERNET)))tetherRequested=false;
            if(tetheredInterfaceRequest!=null&&
                cleanupStep(results,"release_own_interface",()->tetheredInterfaceRequest.getClass().getMethod("release").invoke(tetheredInterfaceRequest))){tetheredInterfaceRequest=null;tetheredInterfaceCallback=null;}
            if(testInterfacesIncluded&&ethernetManager!=null&&
                cleanupStep(results,"restore_test_interface_visibility",()->ethernetManager.getClass().getMethod("setIncludeTestInterfaces",boolean.class).invoke(ethernetManager,false)))testInterfacesIncluded=false;
            if(tapFd!=null&&cleanupStep(results,"close_own_tap",()->tapFd.close()))tapFd=null;
            boolean okay=!tetherRequested&&tetheredInterfaceRequest==null&&!testInterfacesIncluded&&tapFd==null;
            for(int i=0;i<results.length();i++)try{if(!results.getJSONObject(i).getBoolean("ok"))okay=false;}catch(Exception e){okay=false;}
            put("cleanup",results);put("cleanup_ok",okay);
        }finally{
            if(!tetherRequested)tetheringManager=null;
            if(!testInterfacesIncluded)ethernetManager=null;
            if(tapFd==null){tapName=null;clientIpv4=null;gatewayIpv4=null;gatewayMac=null;}
            Binder.restoreCallingIdentity(identity);
        }
    }
}
