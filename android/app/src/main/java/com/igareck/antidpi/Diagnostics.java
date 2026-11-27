/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.*;
import org.json.*;

public final class Diagnostics {
    public static final String DEFAULT_URL="https://speed.cloudflare.com/__down?bytes=1048576";
    public static final String SECOND_URL="https://proof.ovh.net/files/1Mb.dat";
    enum Client {
        KARING("Karing",3066), EXCLAVE("Exclave",2080), V2RAYNG("v2rayNG",10808),
        FLCLASH("FlClash",7890), ORBOT("Orbot",9050), INVIZIBLE("InviZible Pro",9050), OTHER("",0);
        final String label;
        final int suggestedPort;
        Client(String label,int suggestedPort){this.label=label;this.suggestedPort=suggestedPort;}
        String portKey(){return "vpn_port_"+name().toLowerCase(Locale.ROOT);}
    }
    private static final int LIMIT=1048576, MIN_BULK=262144;
    private static final ArrayDeque<JSONObject> RESULTS=new ArrayDeque<>();
    private static volatile String displaySummary="";
    private static volatile boolean stale;
    private static volatile String summaryInstance="";
    private static final ThreadLocal<String> NODE=new ThreadLocal<>();
    private static final ThreadLocal<Client> CLIENT=new ThreadLocal<>();
    private static final ThreadLocal<String> INSTANCE=new ThreadLocal<>();
    private static final ThreadLocal<Integer> TTL=new ThreadLocal<>();
    private static final ThreadLocal<String> RECIPE=new ThreadLocal<>();
    private static final ThreadLocal<Integer> NUMBER=new ThreadLocal<>();
    private static final ThreadLocal<Boolean> UDP=new ThreadLocal<>();
    private static final ThreadLocal<String> QUIC=new ThreadLocal<>(),QUIC_HASH=new ThreadLocal<>(),DISCORD=new ThreadLocal<>(),DISCORD_HASH=new ThreadLocal<>();
    public static synchronized void settingsApplied(String instance){
        if(!displaySummary.isEmpty()&&!instance.equals(summaryInstance))stale=true;
    }
    private static final ThreadLocal<String> BATCH=new ThreadLocal<>();
    public static String displaySummary(){return (stale?"Предыдущий замер — после него настройки применены заново.\n":"")+displaySummary;}
    public static final class Cancellation {
        private final AtomicBoolean stopped=new AtomicBoolean();
        private volatile Socket socket;
        public void cancel(){stopped.set(true);Socket s=socket;if(s!=null)try{s.close();}catch(IOException ignored){}}
        public boolean cancelled(){return stopped.get();}
        void attach(Socket s)throws IOException{socket=s;if(stopped.get()){s.close();throw new IOException("Cancelled");}}
        void check()throws IOException{if(stopped.get())throw new IOException("Cancelled");}
    }
    static final class Result {
        String route,profile,backend,endpoint,stage="CONNECT",error="",tls="",status="",outcome="FAILED";
        String client="";
        String errorKind="",reason="";
        long bytes,elapsed,firstByte=-1,bodyMillis,started=System.currentTimeMillis();
        JSONObject nativeBefore=new JSONObject(),nativeAfter=new JSONObject();
        boolean engineRunning=ProxyService.running;
        boolean udpEnabled=Boolean.TRUE.equals(UDP.get());
        String quicProfile=QUIC.get()==null?"Q1":QUIC.get(),quicHash=QUIC_HASH.get()==null?"":QUIC_HASH.get();
        String discordProfile=DISCORD.get()==null?"D0":DISCORD.get(),discordHash=DISCORD_HASH.get()==null?"":DISCORD_HASH.get();
        boolean complete;
        int port;
        JSONObject json(){
            JSONObject o=new JSONObject();
            try{o.put("route",route).put("profile",profile).put("backend",backend).put("endpoint",endpoint)
                .put("udp_enabled_at_start",udpEnabled).put("quic_profile_at_start",quicProfile).put("quic_recipe_sha256",quicHash)
                .put("discord_profile_at_start",discordProfile).put("discord_recipe_sha256",discordHash)
                .put("diagnostic_transport","TCP_HTTPS").put("validates_quic",false).put("validates_discord_udp",false)
                .put("proxy_port",port).put("stage",stage).put("outcome",outcome).put("error",error)
                .put("error_kind",errorKind).put("reason_ru",reason).put("tls",tls).put("http_status",status).put("bytes",bytes).put("complete",complete)
                .put("elapsed_ms",elapsed).put("first_byte_ms",firstByte).put("body_ms",bodyMillis)
                .put("kib_per_second_total",elapsed>0?bytes*1000.0/1024.0/elapsed:0)
                .put("threshold_bytes",MIN_BULK).put("limit_bytes",LIMIT)
                .put("batch_id",BATCH.get()==null?"":BATCH.get())
                 .put("recorded_at_epoch_ms",System.currentTimeMillis()).put("started_at_epoch_ms",started)
                .put("engine_running_at_start",engineRunning).put("engine_instance",INSTANCE.get()==null?"":INSTANCE.get())
                .put("fake_ttl",TTL.get()==null||TTL.get()<1?JSONObject.NULL:TTL.get())
                .put("karing_node_label",NODE.get()==null?"":NODE.get()).put("node_label_source","user_entered")
                .put("vpn_client_label",client).put("vpn_node_label",NODE.get()==null?"":NODE.get())
                .put("body_budget_ms",60000).put("idle_read_timeout_ms",10000)
                .put("native_metrics_before",nativeBefore).put("native_metrics_after",nativeAfter)
                .put("recipe_sha256",RECIPE.get()==null?"":RECIPE.get()).put("recipe_number",NUMBER.get()==null?0:NUMBER.get())
                .put("catalogue_sha256",backend.equals("nfqws2")?Catalogue.SHA256:"");}catch(JSONException ignored){}
            return o;
        }
    }
    public static void handshake(int port)throws IOException{
        try(Socket socket=new Socket()){
            socket.connect(new InetSocketAddress("127.0.0.1",port),800);socket.setSoTimeout(800);
            socket.getOutputStream().write(new byte[]{5,1,0});
            InputStream in=socket.getInputStream();
            if(in.read()!=5||in.read()!=0)throw new IOException("SOCKS5 greeting rejected");
        }
    }
    public static synchronized String summary(){
        JSONArray a=new JSONArray();for(JSONObject o:RESULTS)a.put(o);return a.toString();
    }
    private static synchronized void remember(Result r){
        // HTTPS through a UDP VPN can prove the chosen combination works, but cannot
        // establish that the TCP/TLS N recipe processed its external connection.
        if(!r.udpEnabled&&r.complete&&r.outcome.equals("BULK_OK")&&r.route.equals("VPN_CLIENT")&&r.backend.equals("nfqws2")&&r.engineRunning
            &&INSTANCE.get()!=null&&INSTANCE.get().equals(r.nativeBefore.optString("instance"))&&INSTANCE.get().equals(r.nativeAfter.optString("instance"))
            &&r.nativeAfter.optLong("input_out")>r.nativeBefore.optLong("input_out")&&r.nativeAfter.optLong("input_in")>r.nativeBefore.optLong("input_in"))
            ProfileStatus.recordVpnSuccess(r.profile,RECIPE.get()==null?"":RECIPE.get(),NODE.get()==null?"":NODE.get(),r.bytes);
        RESULTS.addLast(r.json());while(RESULTS.size()>200)RESULTS.removeFirst();
        AppLog.line("TRAFFIC_RESULT",r.json().toString());
        String route=r.route.equals("DIRECT")?"Напрямую":r.route.equals("ANTIDPI")?"AntiDPI":
            "SOCKS5"+(r.client.isEmpty()?"":" "+r.client)+" (127.0.0.1:"+r.port+")";
        String outcome=r.outcome.equals("BULK_OK")?"файл получен":r.outcome.equals("PARTIAL_BODY")?"получено частично":
            r.outcome.equals("CANCELLED")?"отменено":r.outcome.equals("SMALL_BODY")?"маленький ответ":r.reason.isEmpty()?"проверка адреса не прошла":r.reason;
        String context=r.profile+(r.udpEnabled?" + "+r.quicProfile+" + "+r.discordProfile+" · UDP вкл.":" · UDP выкл.")+(TTL.get()!=null&&TTL.get()>0?" TTL="+TTL.get():"")+" · "+new java.text.SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date());
        String endpoint;try{endpoint=new URL(r.endpoint).getHost();}catch(Exception ignored){endpoint="адрес";}
        String line=context+" · "+endpoint+" · "+String.format(Locale.US,"%s: %.1f КиБ · %.1f с · %.1f КиБ/с · %s",route,r.bytes/1024.0,r.elapsed/1000.0,
            r.elapsed>0?r.bytes*1000.0/1024.0/r.elapsed:0,outcome);
        displaySummary=displaySummary.isEmpty()?line:displaySummary+"\n"+line;
        String[] lines=displaySummary.split("\n");if(lines.length>3)displaySummary=String.join("\n",Arrays.copyOfRange(lines,lines.length-3,lines.length));
        AppLog.line("TRAFFIC",line);
    }
    public static void run(int route,int antiPort,int vpnPort,String url,Cancellation cancel){run(route,antiPort,vpnPort,url,cancel,"");}
    public static void run(int route,int antiPort,int vpnPort,String url,Cancellation cancel,String nodeLabel){run(route,antiPort,vpnPort,url,cancel,nodeLabel,false);}
    public static void run(int route,int antiPort,int vpnPort,String url,Cancellation cancel,String nodeLabel,boolean fallback){
        run(route,antiPort,vpnPort,url,cancel,nodeLabel,fallback,Client.KARING);
    }
    static void run(int route,int antiPort,int vpnPort,String url,Cancellation cancel,String nodeLabel,boolean fallback,Client client){
        displaySummary="";stale=false;BATCH.set(UUID.randomUUID().toString());
        CLIENT.set(client);
        NODE.set(nodeLabel);INSTANCE.set(ProxyService.activeInstance);summaryInstance=ProxyService.activeInstance;
        RECIPE.set(ProxyService.activeRecipeHash);NUMBER.set(ProxyService.activeRecipeNumber);
        UDP.set(ProxyService.activeUdp);QUIC.set(ProxyService.activeQuicId);QUIC_HASH.set(ProxyService.activeQuicHash);DISCORD.set(ProxyService.activeDiscordId);DISCORD_HASH.set(ProxyService.activeDiscordHash);
        TTL.set(0);
        String profile=ProxyService.activeId,backend=ProxyService.activeBackend;
        if(route==3){
            measureRoute("DIRECT",0,url,fallback?alternate(url):null,profile,backend,cancel,null);
            if(!cancel.cancelled())measureRoute("ANTIDPI",antiPort,url,fallback?alternate(url):null,profile,backend,cancel,null);
            if(!cancel.cancelled())measureRoute("VPN_CLIENT",vpnPort,url,fallback?alternate(url):null,profile,backend,cancel,null);
        }else measureRoute(route==0?"DIRECT":route==1?"ANTIDPI":"VPN_CLIENT",
            route==0?0:route==1?antiPort:vpnPort,url,fallback?alternate(url):null,profile,backend,cancel,null);
    }
    private static String alternate(String address){try{return new URL(address).getHost().equalsIgnoreCase("proof.ovh.net")?DEFAULT_URL:SECOND_URL;}catch(Exception ignored){return SECOND_URL;}}
    static Result measureRoute(String route,int port,String primary,String secondary,String profile,String backend,Cancellation cancel,SSLSocketFactory factory){
        Result first=measure(route,port,primary,profile,backend,cancel,factory);
        if(first.outcome.equals("BULK_OK")||cancel.cancelled()||secondary==null||secondary.equals(primary)
            ||first.errorKind.equals("PROXY_PORT_UNAVAILABLE")||first.errorKind.equals("PROXY_NOT_SOCKS5")
            ||first.errorKind.equals("PROXY_AUTH_REQUIRED"))return first;
        AppLog.line("TRAFFIC_FALLBACK","The first HTTPS endpoint failed; checking a different endpoint over the same route.");
        return measure(route,port,secondary,profile,backend,cancel,factory);
    }
    static String errorKind(String stage,Throwable error){
        if(error instanceof SocksException)return ((SocksException)error).kind;
        if(stage.equals("LOCAL_PROXY"))return "PROXY_PORT_UNAVAILABLE";
        if(stage.equals("SOCKS_GREETING"))return "PROXY_NOT_SOCKS5";
        if(stage.equals("SOCKS_CONNECT"))return "PROXY_CONNECT_FAILED";
        StringBuilder all=new StringBuilder();for(Throwable e=error;e!=null;e=e.getCause())all.append(' ').append(e.getClass().getSimpleName()).append(' ').append(e.getMessage());
        String text=all.toString().toLowerCase(Locale.ROOT);
        if(text.contains("subjectalt")||text.contains("no name matching")||text.contains("subject alternative")||text.contains("hostname"))return "TLS_CERTIFICATE_NAME";
        if(text.contains("certpath")||text.contains("certificate_unknown")||text.contains("trust anchor")||text.contains("unable to find valid certification"))return "TLS_CERTIFICATE_TRUST";
        if(stage.equals("TLS_HANDSHAKE"))return error instanceof SocketTimeoutException?"TLS_TIMEOUT":"TLS_HANDSHAKE";
        if(stage.equals("CONNECT"))return "CONNECTION";
        if(stage.equals("HTTP_HEADERS"))return "HTTP_RESPONSE";
        return "HTTP_BODY";
    }
    static String reason(String kind,Throwable error){
        switch(kind){
            case "TLS_CERTIFICATE_NAME":return "TLS: сертификат не соответствует адресу теста";
            case "TLS_CERTIFICATE_TRUST":return "TLS: сертификат сервера не прошёл проверку";
            case "TLS_TIMEOUT":return "TLS: сервер не ответил вовремя";
            case "TLS_HANDSHAKE":return "TLS: соединение закрыто или рукопожатие не завершено";
            case "CONNECTION":return "не удалось подключиться к адресу теста или локальному прокси";
            case "PROXY_PORT_UNAVAILABLE":return "локальный порт недоступен. Запустите клиент, включите его вход SOCKS5 и проверьте порт";
            case "PROXY_NOT_SOCKS5":return "на этом порту нет ответа SOCKS5. Проверьте настройки клиента";
            case "PROXY_AUTH_REQUIRED":return "локальный SOCKS5 отклонил подключение без авторизации";
            case "PROXY_CONNECT_FAILED":return "SOCKS5-клиент не открыл соединение с адресом теста";
            case "HTTP_RESPONSE":return "тестовый сервер вернул неподходящий HTTP-ответ";
            default:return "не удалось получить тело тестового файла";
        }
    }
    /** Factory injection is package-private and only used with a trusted test CA. */
    static Result measure(String route,int port,String address,String profile,String backend,
                          Cancellation cancel,SSLSocketFactory testFactory){
        Result r=new Result();r.route=route;r.port=port;r.profile=profile;r.backend=backend;
        Client client=CLIENT.get();
        if(route.equals("VPN_CLIENT")&&client!=null)r.client=client.label;
        long begin=System.nanoTime(),bodyBegin=0;
        if(backend.equals("nfqws2")&&r.engineRunning){JSONObject m=PacketBridge.freshMetrics();if(m!=null)r.nativeBefore=m;}
        begin=System.nanoTime();r.started=System.currentTimeMillis();
        Socket tunnel=null;
        try{
            URL url=new URL(address);
            if(!"https".equalsIgnoreCase(url.getProtocol())||url.getUserInfo()!=null||url.getHost().isEmpty())
                throw new IOException("Use an HTTPS URL without embedded credentials");
            String host=url.getHost();int remotePort=url.getPort()<0?443:url.getPort();
            String file=url.getFile().isEmpty()?"/":url.getFile();
            if(file.indexOf('\r')>=0||file.indexOf('\n')>=0)throw new IOException("Invalid request target");
            r.endpoint="https://"+host+(remotePort==443?"":":"+remotePort)+url.getPath();
            AppLog.line("TRAFFIC_START","route="+route+"; proxyPort="+port+"; profile="+profile+"; backend="+backend+"; endpoint="+r.endpoint+"; limit="+LIMIT);
            cancel.check();
            tunnel=new Socket();
            cancel.attach(tunnel);
            if(port==0){
                tunnel.connect(new InetSocketAddress(host,remotePort),15000);
            }else openSocks(tunnel,port,host,remotePort,r);
            tunnel.setSoTimeout(15000);r.stage="TLS_HANDSHAKE";
            SSLSocketFactory factory=testFactory==null?(SSLSocketFactory)SSLSocketFactory.getDefault():testFactory;
            try(SSLSocket tls=(SSLSocket)factory.createSocket(tunnel,host,remotePort,true)){
                tls.setSoTimeout(15000);
                SSLParameters params=tls.getSSLParameters();params.setEndpointIdentificationAlgorithm("HTTPS");
                params.setServerNames(Collections.singletonList(new SNIHostName(host)));tls.setSSLParameters(params);
                tls.startHandshake();r.tls=tls.getSession().getProtocol();cancel.check();r.stage="HTTP_HEADERS";
                String authority=host+(remotePort==443?"":":"+remotePort);
                String request="GET "+file+" HTTP/1.1\r\nHost: "+authority
                    +"\r\nConnection: close\r\nRange: bytes=0-1048575\r\nAccept-Encoding: identity\r\nUser-Agent: Igareck-AntiDPI-Android\r\n\r\n";
                tls.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                BufferedInputStream in=new BufferedInputStream(tls.getInputStream(),32768);
                Map<String,String> headers=new LinkedHashMap<>();
                for(int interim=0;interim<5;interim++){
                    r.status=line(in,4096);if(r.firstByte<0)r.firstByte=millis(begin);
                    if(!r.status.matches("HTTP/1\\.[01] [0-9]{3}.*"))throw new IOException("Invalid HTTP response");
                    headers.clear();int headerBytes=0;
                    while(true){
                        String h=line(in,8192);headerBytes+=h.length()+2;
                        if(headerBytes>32768)throw new IOException("Response headers too large");
                        if(h.isEmpty())break;
                        int colon=h.indexOf(':');if(colon>0)headers.put(h.substring(0,colon).toLowerCase(Locale.US),h.substring(colon+1).trim());
                    }
                    int code=Integer.parseInt(r.status.substring(9,12));
                    if(code>=200)break;
                    if(interim==4)throw new IOException("Too many interim responses");
                }
                int code=Integer.parseInt(r.status.substring(9,12));
                if(code!=200&&code!=206)throw new IOException("Body test requires HTTP 200/206; got "+code);
                if(headers.containsKey("content-encoding")&&!headers.get("content-encoding").equalsIgnoreCase("identity"))
                    throw new IOException("Unexpected content encoding");
                r.stage="HTTP_BODY";tls.setSoTimeout(10000);bodyBegin=System.nanoTime();
                long deadline=System.nanoTime()+60000000000L;
                String transfer=headers.getOrDefault("transfer-encoding","").toLowerCase(Locale.US);
                String length=headers.get("content-length");
                if(!transfer.isEmpty()){
                    if(!transfer.equals("chunked"))throw new IOException("Unsupported transfer encoding: "+transfer);
                    while(r.bytes<LIMIT){
                        cancel.check();checkDeadline(deadline);
                        String chunkHeader=line(in,128);int semicolon=chunkHeader.indexOf(';');
                        long chunk=Long.parseLong((semicolon<0?chunkHeader:chunkHeader.substring(0,semicolon)).trim(),16);
                        if(chunk<0)throw new IOException("Invalid chunk size");
                        if(chunk==0){r.complete=true;break;}
                        long amount=Math.min(chunk,LIMIT-r.bytes);
                        copyBody(in,amount,r,cancel,deadline);
                        if(r.bytes>=LIMIT){r.complete=true;break;}
                        if(in.read()!='\r'||in.read()!='\n')throw new IOException("Invalid chunk terminator");
                    }
                }else if(length!=null){
                    long expected=Long.parseLong(length);
                    if(expected<0)throw new IOException("Invalid Content-Length");
                    long target=Math.min(expected,LIMIT);copyBody(in,target,r,cancel,deadline);
                    r.complete=true;
                }else{
                    byte[] buffer=new byte[32768];
                    while(r.bytes<LIMIT){
                        cancel.check();checkDeadline(deadline);
                        int n=in.read(buffer,0,(int)Math.min(buffer.length,LIMIT-r.bytes));
                        if(n<0){r.complete=true;break;}
                        r.bytes+=n;
                    }
                    if(r.bytes>=LIMIT)r.complete=true;
                }
                r.outcome=r.complete&&r.bytes>=MIN_BULK?"BULK_OK":"SMALL_BODY";
                r.stage="COMPLETE";
            }
        }catch(Exception error){
            r.error=error.getClass().getSimpleName()+": "+error.getMessage();
            r.errorKind=errorKind(r.stage,error);r.reason=reason(r.errorKind,error);
            r.outcome=cancel.cancelled()?"CANCELLED":r.bytes>0?"PARTIAL_BODY":"FAILED";
        }finally{
            if(tunnel!=null)try{tunnel.close();}catch(IOException ignored){}
            r.elapsed=millis(begin);if(bodyBegin!=0)r.bodyMillis=millis(bodyBegin);
            if(backend.equals("nfqws2")){JSONObject m=PacketBridge.freshMetrics();if(m!=null)r.nativeAfter=m;}
            remember(r);
        }
        return r;
    }
    private static final class SocksException extends IOException {
        final String kind;
        SocksException(String kind,String message){super(message);this.kind=kind;}
    }
    private static void openSocks(Socket socket,int port,String host,int remotePort,Result result)throws IOException{
        result.stage="LOCAL_PROXY";
        socket.connect(new InetSocketAddress("127.0.0.1",port),2500);
        socket.setSoTimeout(2500);
        result.stage="SOCKS_GREETING";
        OutputStream out=socket.getOutputStream();
        DataInputStream in=new DataInputStream(socket.getInputStream());
        out.write(new byte[]{5,1,0});
        int version=in.readUnsignedByte();
        int method=in.readUnsignedByte();
        if(version!=5)throw new IOException("Unexpected SOCKS version");
        if(method!=0)throw new SocksException("PROXY_AUTH_REQUIRED","SOCKS5 authentication method "+method);
        result.stage="SOCKS_CONNECT";
        socket.setSoTimeout(15000);
        byte[] name=IDN.toASCII(host).getBytes(StandardCharsets.US_ASCII);
        if(name.length<1||name.length>255)throw new IOException("Invalid SOCKS hostname");
        ByteArrayOutputStream request=new ByteArrayOutputStream(name.length+7);
        request.write(new byte[]{5,1,0,3,(byte)name.length});
        request.write(name);
        request.write(remotePort>>>8);
        request.write(remotePort&255);
        out.write(request.toByteArray());
        int replyVersion=in.readUnsignedByte();
        int reply=in.readUnsignedByte();
        if(replyVersion!=5||reply!=0||in.readUnsignedByte()!=0)throw new IOException("SOCKS5 CONNECT reply "+reply);
        int type=in.readUnsignedByte();
        int length=type==1?4:type==4?16:type==3?in.readUnsignedByte():-1;
        if(length<0)throw new IOException("Invalid SOCKS5 address type");
        in.readFully(new byte[length+2]);
    }
    private static void copyBody(InputStream in,long count,Result r,Cancellation cancel,long deadline)throws IOException{
        byte[] buffer=new byte[32768];long left=count,last=System.nanoTime();
        while(left>0){
            cancel.check();checkDeadline(deadline);
            int n=in.read(buffer,0,(int)Math.min(buffer.length,left));
            if(n<0)throw new EOFException("Body ended before the announced length");
            left-=n;r.bytes+=n;
            if(System.nanoTime()-last>1000000000L){
                AppLog.line("TRAFFIC_PROGRESS",r.route+"; received="+r.bytes+"; body_ms="+millis(deadline-60000000000L));last=System.nanoTime();
            }
        }
    }
    private static void checkDeadline(long deadline)throws SocketTimeoutException{
        if(System.nanoTime()>deadline)throw new SocketTimeoutException("Body test exceeded 60 seconds");
    }
    private static long millis(long start){return(System.nanoTime()-start)/1000000L;}
    private static String line(InputStream in,int max)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();int c;
        while((c=in.read())!=-1){
            if(c=='\n')return new String(out.toByteArray(),StandardCharsets.US_ASCII);
            if(c!='\r')out.write(c);
            if(out.size()>max)throw new IOException("HTTP line too long");
        }
        throw new EOFException("Unexpected EOF while reading HTTP line");
    }
    private Diagnostics(){}
}
