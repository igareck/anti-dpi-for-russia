package com.igareck.antidpi;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

public final class DiagnosticsHarness {
    public static void main(String[] args) throws Exception {
        KeyStore trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);
        try(InputStream in=new FileInputStream(args[0])){
            trust.setCertificateEntry("fixture",CertificateFactory.getInstance("X.509").generateCertificate(in));
        }
        TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);
        SSLContext ctx=SSLContext.getInstance("TLS");ctx.init(null,tm.getTrustManagers(),new SecureRandom());
        int tlsPort=Integer.parseInt(args[1]), socksPort=Integer.parseInt(args[2]);
        String base="https://localhost:"+tlsPort;
        int count=0;
        for(String scenario:new String[]{"bulk","chunked","eof","small","no-content","short","redirect","encoding","bad-chunk","stall"}){
            Diagnostics.Result r=Diagnostics.measure("FIXTURE_DIRECT",0,base+"/"+scenario,"N20","nfqws2",new Diagnostics.Cancellation(),ctx.getSocketFactory());
            String expected=scenario.equals("bulk")||scenario.equals("chunked")||scenario.equals("eof")?"BULK_OK":
                scenario.equals("small")?"SMALL_BODY":scenario.equals("short")?"PARTIAL_BODY":"FAILED";
            if(!r.outcome.equals(expected))throw new AssertionError(scenario+": "+r.json());
            if(scenario.equals("bulk")&&r.bytes!=1048576)throw new AssertionError("Wrong bulk byte count");
            if(scenario.equals("chunked")&&r.bytes!=700000)throw new AssertionError("Wrong chunked byte count");
            if(scenario.equals("eof")&&r.bytes!=524288)throw new AssertionError("Wrong EOF body byte count");
            if(scenario.equals("short")&&(r.complete||r.bytes!=5000))throw new AssertionError("Truncated body accepted");
            if(!r.error.isEmpty()&&r.outcome.equals("BULK_OK"))throw new AssertionError("Error marked as success");
            System.out.println(r.json());count++;
        }
        Diagnostics.Result proxy=Diagnostics.measure("FIXTURE_SOCKS",socksPort,base+"/bulk","N20","nfqws2",new Diagnostics.Cancellation(),ctx.getSocketFactory());
        if(!proxy.outcome.equals("BULK_OK")||proxy.bytes!=1048576)throw new AssertionError("SOCKS route: "+proxy.json());
        System.out.println(proxy.json());count++;
        Diagnostics.Result wrongHost=Diagnostics.measure("HOSTNAME_CHECK",0,"https://127.0.0.1:"+tlsPort+"/bulk","N20","nfqws2",new Diagnostics.Cancellation(),ctx.getSocketFactory());
        if(!wrongHost.outcome.equals("FAILED")||!wrongHost.stage.equals("TLS_HANDSHAKE"))throw new AssertionError("Hostname validation bypassed: "+wrongHost.json());
        System.out.println(wrongHost.json());count++;
        Diagnostics.Cancellation cancelled=new Diagnostics.Cancellation();
        Thread cancelThread=new Thread(()->{try{Thread.sleep(250);}catch(InterruptedException e){Thread.currentThread().interrupt();}cancelled.cancel();});
        cancelThread.start();
        Diagnostics.Result cancel=Diagnostics.measure("CANCEL",0,base+"/stall","N20","nfqws2",cancelled,ctx.getSocketFactory());
        cancelThread.join();if(!cancel.outcome.equals("CANCELLED")||cancel.elapsed>3000)throw new AssertionError("Cancellation failed: "+cancel.json());
        System.out.println(cancel.json());count++;
        System.out.println("DIAGNOSTIC_CASES_PASS="+count);
        ProxyService.activeId="N20";ProxyService.activeInstance="context-one";
        Diagnostics.Cancellation noNetwork=new Diagnostics.Cancellation();noNetwork.cancel();
        Diagnostics.run(1,1080,3066,base+"/bulk",noNetwork,"Finland BL-64");
        org.json.JSONArray saved=new org.json.JSONArray(Diagnostics.summary());org.json.JSONObject last=saved.getJSONObject(saved.length()-1);
        if(!last.getString("profile").equals("N20")||!last.getString("engine_instance").equals("context-one")||!last.getString("karing_node_label").equals("Finland BL-64"))throw new AssertionError("Measurement context missing: "+last);
        ProxyService.activeId="N32";ProxyService.activeInstance="context-two";Diagnostics.settingsApplied("context-two");
        if(!Diagnostics.displaySummary().contains("Предыдущий замер")||!last.getString("profile").equals("N20"))throw new AssertionError("Old result was attributed to a new recipe");
        System.out.println("DIAGNOSTIC_CONTEXT_PASS=true");
        ProxyService.activeId="N484";ProxyService.activeBackend="nfqws2";ProxyService.activeRecipeNumber=484;ProxyService.activeRecipeHash="e3defe6046877e3275c9a109eab7b1ab308cedcd6f0d13636072a98cccdc7dc1";
        Diagnostics.run(1,1080,3066,base+"/bulk",noNetwork,"node-484");
        saved=new org.json.JSONArray(Diagnostics.summary());last=saved.getJSONObject(saved.length()-1);
        if(last.getInt("recipe_number")!=484||!last.getString("recipe_sha256").equals(ProxyService.activeRecipeHash)||!last.getString("catalogue_sha256").equals(Catalogue.SHA256))throw new AssertionError("Recipe context missing: "+last);
        ProxyService.activeRecipeHash="changed";if(last.getString("recipe_sha256").equals("changed"))throw new AssertionError("Old recipe measurement changed");
        System.out.println("DIAGNOSTIC_RECIPE_CONTEXT_PASS=true");
        PacketBridge.fixtureInstance="context-two";
        Diagnostics.Result evidence=Diagnostics.measure("VPN_CLIENT",socksPort,base+"/bulk","N484","nfqws2",new Diagnostics.Cancellation(),ctx.getSocketFactory());
        if(!evidence.outcome.equals("BULK_OK")||ProfileStatus.records!=1||ProfileStatus.hash.equals("changed"))throw new AssertionError("Valid evidence not saved with captured hash");
        Diagnostics.measure("ANTIDPI",socksPort,base+"/bulk","N484","nfqws2",new Diagnostics.Cancellation(),ctx.getSocketFactory());
        if(ProfileStatus.records!=1)throw new AssertionError("Direct AntiDPI test was marked as VPN success");
        PacketBridge.fixtureInstance="other-instance";
        Diagnostics.measure("VPN_CLIENT",socksPort,base+"/bulk","N484","nfqws2",new Diagnostics.Cancellation(),ctx.getSocketFactory());
        if(ProfileStatus.records!=1)throw new AssertionError("Mismatched instance was marked successful");
        PacketBridge.fixtureInstance="context-two";
        Diagnostics.measure("VPN_CLIENT",socksPort,base+"/short","N484","nfqws2",new Diagnostics.Cancellation(),ctx.getSocketFactory());
        if(ProfileStatus.records!=1)throw new AssertionError("Partial file was marked successful");
        System.out.println("DIAGNOSTIC_EVIDENCE_GATES_PASS=4");
        ProxyService.activeUdp=true;ProxyService.activeQuicId="Q4";ProxyService.activeQuicHash="q4-fixture-hash";
        Diagnostics.run(1,1080,3066,base+"/bulk",noNetwork,"udp-node");
        saved=new org.json.JSONArray(Diagnostics.summary());last=saved.getJSONObject(saved.length()-1);
        if(!last.getBoolean("udp_enabled_at_start")||!last.getString("quic_profile_at_start").equals("Q4")||!last.getString("quic_recipe_sha256").equals("q4-fixture-hash"))throw new AssertionError("Applied UDP/QUIC context lost");
        if(!last.getString("diagnostic_transport").equals("TCP_HTTPS")||last.getBoolean("validates_quic"))throw new AssertionError("HTTPS misreported as QUIC test");
        ProxyService.activeQuicId="Q5";ProxyService.activeUdp=false;
        if(!last.getString("quic_profile_at_start").equals("Q4")||!last.getBoolean("udp_enabled_at_start"))throw new AssertionError("Prior UDP context changed");
        Diagnostics.measure("VPN_CLIENT",socksPort,base+"/bulk","N484","nfqws2",new Diagnostics.Cancellation(),ctx.getSocketFactory());
        if(ProfileStatus.records!=1)throw new AssertionError("UDP VPN success was attributed to a TCP recipe");
        System.out.println("DIAGNOSTIC_UDP_CONTEXT_CHECKS_PASS=4");
        if(!wrongHost.errorKind.equals("TLS_CERTIFICATE_NAME")||Diagnostics.displaySummary().contains("нет результата"))throw new AssertionError("Opaque TLS failure reason");
        Diagnostics.Result fallback=Diagnostics.measureRoute("FIXTURE_FALLBACK",0,"https://127.0.0.1:"+tlsPort+"/bulk",base+"/bulk","N20","nfqws2",new Diagnostics.Cancellation(),ctx.getSocketFactory());
        if(!fallback.outcome.equals("BULK_OK"))throw new AssertionError("Independent HTTPS fallback did not run");
        org.json.JSONArray all=new org.json.JSONArray(Diagnostics.summary());
        if(!all.getJSONObject(all.length()-2).getString("error_kind").equals("TLS_CERTIFICATE_NAME"))throw new AssertionError("First endpoint failure was erased");
        int prior=all.length();Diagnostics.Cancellation stopNow=new Diagnostics.Cancellation();stopNow.cancel();
        Diagnostics.measureRoute("FIXTURE_FALLBACK",0,base+"/bulk",base+"/bulk?second=1","N20","nfqws2",stopNow,ctx.getSocketFactory());
        if(new org.json.JSONArray(Diagnostics.summary()).length()!=prior+1)throw new AssertionError("Cancelled test attempted fallback");
        if(!Diagnostics.errorKind("TLS_HANDSHAKE",new java.net.SocketTimeoutException("Read timed out")).equals("TLS_TIMEOUT"))throw new AssertionError("TLS timeout mislabeled");
        System.out.println("DIAGNOSTIC_FALLBACK_CHECKS_PASS=4");

        InetAddress loopback=InetAddress.getByName("127.0.0.1");
        try(Socket reserved=new Socket()){
            reserved.bind(new InetSocketAddress(loopback,0));
            int inactivePort=reserved.getLocalPort();
            int before=new org.json.JSONArray(Diagnostics.summary()).length();
            Diagnostics.run(2,1080,inactivePort,base+"/bulk",new Diagnostics.Cancellation(),"Test node",true,Diagnostics.Client.EXCLAVE);
            saved=new org.json.JSONArray(Diagnostics.summary());last=saved.getJSONObject(saved.length()-1);
            if(saved.length()!=before+1||!last.getString("error_kind").equals("PROXY_PORT_UNAVAILABLE")
                ||!last.getString("vpn_client_label").equals("Exclave")||last.getInt("proxy_port")!=inactivePort
                ||!last.getString("vpn_node_label").equals("Test node")||!last.getString("stage").equals("LOCAL_PROXY")
                ||!Diagnostics.displaySummary().contains("SOCKS5 Exclave (127.0.0.1:"+inactivePort+")"))
                throw new AssertionError("A local refusal must identify its client and avoid a second endpoint: "+last);
        }
        probeGreeting(loopback, base, ctx, new byte[]{'H','T'},"PROXY_NOT_SOCKS5");
        probeGreeting(loopback, base, ctx, new byte[]{5,(byte)255},"PROXY_AUTH_REQUIRED");
        System.out.println("DIAGNOSTIC_CLIENT_PROXY_CHECKS_PASS=3");
    }

    private static void probeGreeting(InetAddress local,String base,SSLContext ctx,byte[] response,String expected)throws Exception{
        try(ServerSocket listener=new ServerSocket(0,1,local)){
            listener.setSoTimeout(5000);
            CompletableFuture<Void> peer=CompletableFuture.runAsync(()->{
                try(Socket socket=listener.accept()){
                    socket.setSoTimeout(1000);
                    byte[] greeting=new byte[3];
                    new DataInputStream(socket.getInputStream()).readFully(greeting);
                    if(!java.util.Arrays.equals(greeting,new byte[]{5,1,0}))throw new AssertionError("Invalid SOCKS5 greeting");
                    socket.getOutputStream().write(response);
                }catch(IOException e){throw new UncheckedIOException(e);}
            });
            Diagnostics.Result result=Diagnostics.measureRoute("VPN_CLIENT",listener.getLocalPort(),base+"/bulk",
                base+"/bulk?fallback","N20","nfqws2",new Diagnostics.Cancellation(),ctx.getSocketFactory());
            peer.get(5,TimeUnit.SECONDS);
            if(!expected.equals(result.errorKind)||!result.stage.equals("SOCKS_GREETING"))
                throw new AssertionError("Unexpected local greeting failure: "+result.json());
        }
    }
}
