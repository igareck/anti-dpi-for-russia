/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.zip.*;
import javax.net.ssl.*;
import org.json.*;

/** Android port of Linux youtube_probe.py, pinned in RESEARCH_SOURCES.json.
 * Fresh SOCKS/TAP connections replace Linux source-port/firewall isolation.
 * System certificate trust and HTTPS hostname verification are always enabled. */
public final class YoutubeProbe {
    static final int MINIMUM_BYTES=100*1024,SAMPLE_LIMIT=1048576;
    private static final int ORIGIN_SAMPLE_LIMIT=128*1024;

    static final ScheduledExecutorService CLOCK=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"https-deadline");t.setDaemon(true);return t;});
    private static final ExecutorService ABORT=Executors.newSingleThreadExecutor(task->{
        Thread thread=new Thread(task,"scan-socket-abort");
        thread.setDaemon(true);
        return thread;
    });
    interface Lookup {
        byte[] resolve(String host, Cancellation cancel, long deadline) throws IOException;
    }
    private static final Pattern CAPTCHA=Pattern.compile("<(?:form|div)[^>]*(?:id|class)\\s*=\\s*['\" ][^>]*\\b(?:g-recaptcha|captcha-form)\\b|<form[^>]*action\\s*=\\s*['\"][^'\"]*(?:/sorry/|consent\\.google\\.)",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
    public static final class Cancellation {
        private boolean cancelled;
        private final Set<Closeable> sockets=new HashSet<>();
        public synchronized boolean stopped(){return cancelled||Thread.currentThread().isInterrupted();}
        synchronized void attach(Closeable socket)throws IOException{
            if(stopped()){
                abort(Collections.singletonList(socket));
                throw new InterruptedIOException("CANCELLED");
            }
            sockets.add(socket);
        }
        synchronized void detach(Closeable socket){sockets.remove(socket);}
        public void cancel(){
            List<Closeable> pending;
            synchronized(this){
                cancelled=true;
                if(sockets.isEmpty())return;
                pending=new ArrayList<>(sockets);
                sockets.clear();
            }
            abort(pending);
        }
        private static void abort(List<Closeable> pending){
            // SCAN_STOP can arrive on Android's main thread. Even an SSL close
            // can write to the network. Abort the captured transports off-thread.
            ABORT.execute(()->{
                for(Closeable socket:pending){
                    try{close(socket);}
                    catch(RuntimeException e){AppLog.error("Cancel site probe",e);}
                }
            });
        }
        void check()throws IOException{if(stopped())throw new InterruptedIOException("CANCELLED");}
    }
    public static final class Result {
        public boolean pass,technical,originReachable;
        public int bytes,http;
        public String reason="NOT_STARTED",error="",url="https://www.youtube.com/",mode="",tls="",contentType="",encoding="";
        public long milliseconds;
        public final List<Result> modes=new ArrayList<>();
        public JSONObject json()throws JSONException{
            JSONObject o=new JSONObject().put("pass",pass).put("technical",technical).put("bytes",bytes).put("http",http).put("reason",reason).put("error",error)
                .put("url",url).put("mode",mode).put("tls",tls).put("content_type",contentType).put("content_encoding",encoding).put("seconds",milliseconds/1000.0).put("backend","android-ssl-socks-tap");
            if(originReachable)o.put("origin_reachable",true);
            JSONArray a=new JSONArray();for(Result r:modes)a.put(r.json());if(!modes.isEmpty())o.put("modes",a);return o;
        }
    }
    static Result probe(int port,Cancellation cancel,WebTarget target,ScanDoh dns){
        return probe(port,cancel,25000,target.probeUrl(),(SSLSocketFactory)SSLSocketFactory.getDefault(),target,dns::resolve);
    }
    static Result probe(int port,Cancellation cancel,int timeout,String url,SSLSocketFactory factory,WebTarget target,Lookup dns){
        long started=System.nanoTime(),deadline=started+TimeUnit.MILLISECONDS.toNanos(timeout);
        List<Result> attempts=new ArrayList<>();
        boolean origin=target.testsOrigin();
        for(String mode:new String[]{"AUTO","TLS1.2"}){
            if(cancel.stopped()||deadline-System.nanoTime()<100000000L)break;
            long until=mode.equals("AUTO")?Math.min(deadline,started+TimeUnit.MILLISECONDS.toNanos(Math.min(12000,(int)(timeout*.55)))):deadline;
            Result r=mode(port,cancel,mode,until,url,factory,target,dns,origin);attempts.add(r);
            if(r.pass||r.technical||Arrays.asList("DNS_ERROR","CERTIFICATE_VERIFY_FAILED","HTTP_STATUS","CAPTCHA_OR_CONSENT","UNEXPECTED_FINAL_DOMAIN","UNEXPECTED_PROBE_URL").contains(r.reason))break;
        }
        Result best=attempts.isEmpty()?new Result():attempts.get(0);
        for(Result r:attempts)if(r.pass||r.bytes>best.bytes||(origin&&best.http==0&&r.http!=0)){best=r;if(r.pass)break;}
        if(cancel.stopped()){best.pass=false;best.reason="CANCELLED";}
        Result answer=new Result();answer.pass=best.pass;answer.technical=best.technical;answer.bytes=best.bytes;answer.http=best.http;answer.reason=best.reason;answer.error=best.error;
        answer.url=best.url;answer.mode=best.mode;answer.tls=best.tls;answer.contentType=best.contentType;answer.encoding=best.encoding;answer.modes.addAll(attempts);
        answer.originReachable=best.originReachable&&!cancel.stopped();
        answer.milliseconds=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);return answer;
    }
    static boolean allowed(URI u){return WebTarget.YOUTUBE.allows(u);}
    private static Result mode(int port,Cancellation cancel,String mode,long deadline,String address,SSLSocketFactory factory,WebTarget target,Lookup dns,boolean origin){
        Result r=new Result();r.mode=origin?mode+"/ORIGIN":mode;
        for(int redirect=0;redirect<4;redirect++){
            Socket raw=null;SSLSocket tls=null;ScheduledFuture<?> timer=null;
            try{
                cancel.check();URI u=URI.create(address);r.url=address;
                if(!target.allows(u)){r.reason="UNEXPECTED_FINAL_DOMAIN";return r;}
                if(origin&&!target.probeUrl().equals(address)){r.reason="UNEXPECTED_PROBE_URL";return r;}
                String host=u.getHost();int remote=u.getPort()<0?443:u.getPort();
                byte[] ipv4=dns.resolve(host,cancel,deadline);
                raw=new Socket();cancel.attach(raw);final Socket owned=raw;
                timer=CLOCK.schedule(()->close(owned),Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
                raw.connect(new InetSocketAddress("127.0.0.1",port),Math.min(5000,left(deadline)));raw.setSoTimeout(left(deadline));raw.setTcpNoDelay(true);
                socks(raw,ipv4,remote);
                tls=httpsSocket(raw,host,remote,factory);tls.setSoTimeout(left(deadline));
                if(mode.equals("TLS1.2"))tls.setEnabledProtocols(new String[]{"TLSv1.2"});
                tls.startHandshake();r.tls=tls.getSession().getProtocol();cancel.check();
                String path=u.getRawPath();if(path==null||path.isEmpty())path="/";if(u.getRawQuery()!=null)path+="?"+u.getRawQuery();
                String request=request(u,path,origin);
                tls.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));tls.getOutputStream().flush();
                InputStream in=new BufferedInputStream(tls.getInputStream(),16384);
                Map<String,String> headers;int interim=0;
                do{
                    String status=line(in);if(!status.matches("HTTP/1\\.[01] [0-9]{3}( .*)?"))throw new IOException("Invalid HTTP status");r.http=Integer.parseInt(status.substring(9,12));
                    headers=headers(in);
                    if(++interim>5)throw new IOException("Too many interim responses");
                }while(r.http>=100&&r.http<200);
                if(r.http>=300&&r.http<400&&r.http!=304){
                    if(origin){r.reason="HTTP_STATUS";return r;}
                    if(!headers.containsKey("location")){r.reason="REDIRECT_WITHOUT_LOCATION";return r;}
                    address=u.resolve(headers.get("location")).toString();continue;
                }
                r.contentType=headers.getOrDefault("content-type","");r.encoding=headers.getOrDefault("content-encoding","");
                if(r.http!=200&&(r.http!=206||origin)){r.reason="HTTP_STATUS";return r;}
                String mediaType=r.contentType.split(";",2)[0].trim().toLowerCase(Locale.ROOT);
                if(origin?!mediaType.equals("text/plain"):!mediaType.equals("text/html")&&!mediaType.equals("application/xhtml+xml")){
                    r.reason=origin?"NOT_ORIGIN_TEXT":"NOT_HTML";return r;
                }
                String transfer=headers.getOrDefault("transfer-encoding","").toLowerCase(Locale.ROOT);
                boolean chunked=transfer.equals("chunked");if(!transfer.isEmpty()&&!chunked)throw new IOException("Unsupported Transfer-Encoding");
                String contentLength=headers.get("content-length");
                boolean fixedLength=!chunked&&contentLength!=null;
                long remaining=fixedLength?Long.parseLong(contentLength):Long.MAX_VALUE;
                if(remaining<0)throw new IOException("Invalid Content-Length");
                ByteArrayOutputStream body=new ByteArrayOutputStream();byte[] buf=new byte[16384];long chunkLeft=0;
                boolean endChunk=false,endStream=false;
                int limit=origin?ORIGIN_SAMPLE_LIMIT:SAMPLE_LIMIT;
                while(body.size()<limit&&remaining>0){
                    cancel.check();tls.setSoTimeout(left(deadline));
                    if(chunked&&chunkLeft==0){
                        String count=line(in).split(";",2)[0].trim();
                        chunkLeft=Long.parseLong(count,16);
                        if(chunkLeft<0)throw new IOException("Invalid chunk size");
                        if(chunkLeft==0){headers(in);endChunk=true;break;}
                    }
                    int count=(int)Math.min(buf.length,Math.min(limit-body.size(),Math.min(remaining,chunked?chunkLeft:Long.MAX_VALUE)));
                    int n=in.read(buf,0,count);
                    if(n<0){
                        if(chunked||fixedLength)throw new EOFException("Truncated HTTP body");
                        endStream=true;break;
                    }
                    body.write(buf,0,n);r.bytes=body.size();if(fixedLength)remaining-=n;
                    if(chunked){chunkLeft-=n;if(chunkLeft==0&&!line(in).isEmpty())throw new IOException("Invalid chunk boundary");}
                    if(!origin&&r.bytes>=MINIMUM_BYTES){r.reason=signature(body.toByteArray(),r.encoding,target);if(r.reason.equals("THRESHOLD_REACHED")){r.pass=true;return r;}
                        if(Arrays.asList("CAPTCHA_OR_CONSENT","UNSUPPORTED_CONTENT_ENCODING","INVALID_CONTENT_ENCODING").contains(r.reason))return r;}
                }
                byte[] html=body.toByteArray();
                boolean identity=r.encoding.isEmpty()||r.encoding.equalsIgnoreCase("identity");
                boolean framed=endChunk||(fixedLength&&remaining==0);
                if(origin){
                    String rules=new String(html,StandardCharsets.UTF_8);
                    r.originReachable=identity&&(framed||endStream)&&ROBOTS_AGENT.matcher(rules).find()&&ROBOTS_RULE.matcher(rules).find();
                    r.pass=r.originReachable;
                    r.reason=r.originReachable?"ORIGIN_REACHABLE":"NOT_ORIGIN_TEXT";
                    return r;
                }
                r.reason=signature(html,r.encoding,target);
                if(r.reason.equals("THRESHOLD_REACHED")){
                    boolean complete=framed||(endStream&&identity&&HTML_END.matcher(new String(html,StandardCharsets.UTF_8)).find());
                    if(target.acceptsCompleteShortPage()&&r.http==200&&identity&&complete){
                        r.pass=true;r.reason="COMPLETE_HTML";
                    }else r.reason="SHORT_COMPLETE_RESPONSE";
                }
                return r;
            }catch(Exception e){
                r.error=e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage());
                if(cancel.stopped())r.reason="CANCELLED";
                else if(e instanceof UnknownHostException)r.reason="DNS_ERROR";
                else if(certificate(e))r.reason="CERTIFICATE_VERIFY_FAILED";
                else if(e instanceof SocketTimeoutException||System.nanoTime()>=deadline)r.reason="TIMEOUT";
                else if(e instanceof IllegalArgumentException){r.reason="LOCAL_CONFIGURATION_ERROR";r.technical=true;}
                else r.reason="TRANSFER_ERROR";
                return r;
            }finally{if(timer!=null)timer.cancel(false);close(tls);close(raw);cancel.detach(raw);}
        }
        r.reason="TOO_MANY_REDIRECTS";return r;
    }
    private static String request(URI uri,String path,boolean origin){
        String common="GET "+path+" HTTP/1.1\r\nHost: "+uri.getRawAuthority()+"\r\n";
        if(origin)return common+"User-Agent: Igareck-AntiDPI-Android/1.0\r\nAccept: text/plain\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n";
        return common+"User-Agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/147.0.0.0 Safari/537.36\r\n"+
            "Accept: text/html,application/xhtml+xml\r\nAccept-Encoding: identity\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n";
    }
    private static final Pattern ROBOTS_AGENT=Pattern.compile("(?im)^\\s*User-agent\\s*:\\s*[^\\r\\n]+");
    private static final Pattern ROBOTS_RULE=Pattern.compile("(?im)^\\s*(?:Disallow|Allow)\\s*:\\s*[^\\r\\n]*");
    private static boolean certificate(Throwable e){for(Throwable x=e;x!=null;x=x.getCause())if(x instanceof CertificateException||x instanceof SSLPeerUnverifiedException)return true;return false;}
    static SSLSocket httpsSocket(Socket raw,String host,int port,SSLSocketFactory factory)throws IOException{
        SSLSocket tls=(SSLSocket)factory.createSocket(raw,host,port,true);
        SSLParameters params=tls.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        params.setServerNames(Collections.singletonList(new SNIHostName(host)));
        params.setApplicationProtocols(new String[]{"http/1.1"});
        tls.setSSLParameters(params);
        return tls;
    }
    static int left(long deadline)throws SocketTimeoutException{long ms=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());if(ms<=0)throw new SocketTimeoutException("HTTPS deadline");return(int)Math.min(Integer.MAX_VALUE,Math.max(1,ms));}
    private static void socks(Socket s,byte[] address,int port)throws IOException{
        if(address.length!=4)throw new IllegalArgumentException("SOCKS scan requires IPv4");
        InputStream in=s.getInputStream();OutputStream out=s.getOutputStream();out.write(new byte[]{5,1,0});out.flush();if(in.read()!=5||in.read()!=0)throw new IOException("SOCKS greeting rejected");
        out.write(new byte[]{5,1,0,1});out.write(address);out.write(port>>>8);out.write(port&255);out.flush();
        int ver=in.read(),rep=in.read(),reserved=in.read(),atyp=in.read();
        if(ver!=5||rep!=0||reserved!=0)throw new IOException("SOCKS CONNECT reply="+rep);
        int count=atyp==1?4:atyp==4?16:atyp==3?in.read():-1;if(count<0)throw new IOException("Invalid SOCKS address");for(int i=0;i<count+2;i++)if(in.read()<0)throw new EOFException("SOCKS reply");
    }
    static Map<String,String> headers(InputStream in) throws IOException {
        Map<String,String> headers=new HashMap<>();
        String header;
        int size=0;
        while(!(header=line(in)).isEmpty()){
            size+=header.length()+2;
            if(size>65536)throw new IOException("HTTP headers too large");
            int colon=header.indexOf(':');
            if(colon<1)throw new IOException("Invalid HTTP header");
            String key=header.substring(0,colon).toLowerCase(Locale.ROOT);
            String value=header.substring(colon+1).trim();
            headers.merge(key,value,(a,b)->a+","+b);
        }
        return headers;
    }
    static String line(InputStream in)throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();int prev=-1;
        for(int n=0;n<65536;n++){int c=in.read();if(c<0)throw new EOFException("HTTP line");if(prev=='\r'&&c=='\n'){byte[] a=b.toByteArray();return new String(a,0,a.length-1,StandardCharsets.ISO_8859_1);}b.write(c);prev=c;}
        throw new IOException("HTTP line too large");
    }
    static String signature(byte[] body,String encoding){return signature(body,encoding,WebTarget.YOUTUBE);}
    static String signature(byte[] body,String encoding,WebTarget target){
        String text;try{text=new String(decoded(body,encoding.toLowerCase(Locale.ROOT).trim()),StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);}
        catch(UnsupportedOperationException e){return "UNSUPPORTED_CONTENT_ENCODING";}catch(IOException e){return "INVALID_CONTENT_ENCODING";}
        if(CAPTCHA.matcher(text).find())return "CAPTCHA_OR_CONSENT";
        boolean html=Pattern.compile("<!doctype\\s+html|<html\\b").matcher(text).find();
        if(target==WebTarget.DISCORD){
            if(Pattern.compile("<title[^>]*>[^<]*(?:just a moment|attention required)|(?:id|class)\\s*=\\s*['\"][^'\"]*(?:cf-challenge|challenge-form|hcaptcha)").matcher(text).find())return "CAPTCHA_OR_CONSENT";
            boolean identity=Pattern.compile("<title[^>]*>[^<]*discord").matcher(text).find();
            boolean service=text.contains("discord.com/app")||text.contains("discord.com/assets/")||text.contains("/channels/@me")||text.contains("discord.com/login");
            return html&&identity&&service?"THRESHOLD_REACHED":"NOT_DISCORD_HTML";
        }
        if(target!=WebTarget.YOUTUBE)return otherSiteSignature(text,html,target);
        boolean bootstrap=text.contains("ytcfg.set")||text.contains("ytinitialdata")||text.contains("<ytd-app");
        boolean identity=text.contains("innertube_")||Pattern.compile("<title[^>]*>[^<]*youtube").matcher(text).find();
        return html&&bootstrap&&identity?"THRESHOLD_REACHED":"NOT_YOUTUBE_HTML";
    }
    private static final Pattern TITLE=Pattern.compile("<title(?:\\s[^>]*)?>([^<]*)</title\\s*>");
    private static final Pattern WEB_CHALLENGE=Pattern.compile(
        "<title[^>]*>[^<]*(?:just a moment|attention required|access denied|security check|login checkpoint)"
        +"|(?:id|class)\\s*=\\s*['\"][^'\"]*(?:cf-challenge|challenge-form)");
    private static String otherSiteSignature(String text,boolean html,WebTarget target){
        if(WEB_CHALLENGE.matcher(text).find())return "CAPTCHA_OR_CONSENT";
        java.util.regex.Matcher title=TITLE.matcher(text);
        String heading=title.find()?title.group(1).trim():"";
        boolean valid=false;
        switch(target){
            case LINKEDIN:
                valid=heading.contains("linkedin")&&(text.contains("linkedin.com/")||text.contains("licdn.com/"));
                break;
            case X:
                valid=(heading.equals("x")||heading.contains("/ x")||heading.startsWith("x.")||heading.contains("twitter"))
                    &&(text.contains("twimg.com/")||text.contains("twitter.com/")||text.contains("x.com/"));
                break;
            case THREADS:
                valid=heading.contains("threads")&&(text.contains("threads.com/")||text.contains("threads.net/")
                    ||text.contains("cdninstagram.com/")||text.contains("fbcdn.net/"));
                break;
            default: throw new AssertionError(target);
        }
        return html&&valid?"THRESHOLD_REACHED":"NOT_"+target.name()+"_HTML";
    }
    private static final Pattern HTML_END=Pattern.compile("</html\\s*>\\s*$",Pattern.CASE_INSENSITIVE);

    private static byte[] decoded(byte[] data,String encoding)throws IOException{
        if(encoding.isEmpty()||encoding.equals("identity"))return Arrays.copyOf(data,Math.min(data.length,SAMPLE_LIMIT));
        if(encoding.equals("gzip")){
            ByteArrayOutputStream b=new ByteArrayOutputStream();try(InputStream in=new GZIPInputStream(new ByteArrayInputStream(data))){byte[] buf=new byte[8192];while(b.size()<SAMPLE_LIMIT){int n;try{n=in.read(buf,0,Math.min(buf.length,SAMPLE_LIMIT-b.size()));}catch(EOFException truncated){break;}if(n<0)break;b.write(buf,0,n);}}return b.toByteArray();
        }
        if(!encoding.equals("deflate"))throw new UnsupportedOperationException();
        try{return inflate(data,false);}catch(IOException e){return inflate(data,true);}
    }
    private static byte[] inflate(byte[] data,boolean raw)throws IOException{
        Inflater inflater=new Inflater(raw);try{inflater.setInput(data);byte[] buf=new byte[SAMPLE_LIMIT];int length=0;while(length<buf.length){int n=inflater.inflate(buf,length,buf.length-length);length+=n;if(n==0){if(inflater.needsDictionary())throw new IOException("Deflate dictionary");break;}}return Arrays.copyOf(buf,length);}catch(DataFormatException e){throw new IOException(e);}finally{inflater.end();}
    }
    static void close(Closeable c){if(c!=null)try{c.close();}catch(IOException ignored){}}
    private YoutubeProbe(){}
}
