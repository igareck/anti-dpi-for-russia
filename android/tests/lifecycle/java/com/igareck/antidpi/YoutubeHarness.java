/* Production probe/scanner, with an explicitly local SOCKS + TLS fixture. */
package com.igareck.antidpi;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;
import javax.net.ssl.*;
import org.json.*;

public final class YoutubeHarness {
 static int checks;static final JSONArray results=new JSONArray();
 static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
 static byte[] html;
 static byte[] response(int status,String headers,byte[] body){
  byte[] head=("HTTP/1.1 "+status+" Fixture\r\n"+headers+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
  byte[] all=Arrays.copyOf(head,head.length+body.length);System.arraycopy(body,0,all,head.length,body.length);return all;
 }
 static byte[] normal(byte[] b){return response(200,"Content-Type: text/html\r\nContent-Length: "+b.length,b);}
 static final class Fixture implements AutoCloseable {
  final ServerSocket server=new ServerSocket(0,5,InetAddress.getByName("127.0.0.1"));final AtomicInteger accepted=new AtomicInteger();final SSLContext ctx;
  volatile boolean closed;byte[] data;int stall;boolean firstClose,redirect;final List<Socket> sockets=new CopyOnWriteArrayList<>();
  Fixture(SSLContext ctx,byte[] response)throws Exception{this.ctx=ctx;data=response;
   Thread listener=new Thread(()->{while(!closed)try{Socket socket=server.accept();sockets.add(socket);int n=accepted.incrementAndGet();Thread t=new Thread(()->serve(socket,n));t.setDaemon(true);t.start();}catch(IOException e){if(!closed)throw new RuntimeException(e);}});listener.setDaemon(true);listener.start();
  }
  void serve(Socket raw,int n){try(Socket s=raw){s.setSoTimeout(2000);DataInputStream in=new DataInputStream(s.getInputStream());byte[] greeting=new byte[3];in.readFully(greeting);s.getOutputStream().write(new byte[]{5,0});
   byte[] request=new byte[4];in.readFully(request);
   if(!Arrays.equals(request,new byte[]{5,1,0,1}))throw new IOException("Expected numeric IPv4 SOCKS CONNECT");
   byte[] host=new byte[4];in.readFully(host);in.readUnsignedShort();
   if(!Arrays.equals(host,new byte[]{9,9,9,9}))throw new IOException("Wrong address from the resolver fixture");
   s.getOutputStream().write(new byte[]{5,0,0,1,127,0,0,1,1,1});
   if(firstClose&&n==1)return;
   try(SSLSocket tls=(SSLSocket)ctx.getSocketFactory().createSocket(s,"localhost",server.getLocalPort(),true)){
    tls.setUseClientMode(false);tls.startHandshake();InputStream req=tls.getInputStream();int state=0;while(state<4){int b=req.read();if(b<0)return;state=(state==0&&b==13)?1:(state==1&&b==10)?2:(state==2&&b==13)?3:(state==3&&b==10)?4:0;}
    if(stall>0){Thread.sleep(stall);return;}
    byte[] body=redirect&&n==1?response(302,"Location: /redirect\r\nContent-Length: 0",new byte[0]):data;
    tls.getOutputStream().write(body);tls.getOutputStream().flush();
   }
  }catch(Exception ignored){}}
  public void close()throws Exception{closed=true;server.close();for(Socket s:sockets)try{s.close();}catch(Exception ignored){}}
 }
 static YoutubeProbe.Result probe(SSLContext ctx,byte[] response,boolean trust)throws Exception{
  try(Fixture f=new Fixture(ctx,response)){return measure(f.server.getLocalPort(),new YoutubeProbe.Cancellation(),1500,"https://www.youtube.com/",trust?ctx.getSocketFactory():(SSLSocketFactory)SSLSocketFactory.getDefault());}
 }
 static void expect(SSLContext ctx,String name,byte[] response,String reason,boolean pass)throws Exception{
  YoutubeProbe.Result r=probe(ctx,response,true);check(r.pass==pass&&r.reason.equals(reason),name+": "+r.json());results.put(new JSONObject().put("name",name).put("result",r.json()));
 }
 static byte[] compressed(boolean gzip,boolean raw)throws Exception{
  ByteArrayOutputStream out=new ByteArrayOutputStream();try(OutputStream zip=gzip?new GZIPOutputStream(out):new DeflaterOutputStream(out,new Deflater(6,raw))){zip.write(html);}return out.toByteArray();
 }
 static final YoutubeProbe.Lookup LOOKUP=(host,cancel,deadline)->new byte[]{9,9,9,9};
 static YoutubeProbe.Result measure(int port,YoutubeProbe.Cancellation cancel,int timeout,String url,SSLSocketFactory factory){
  return YoutubeProbe.probe(port,cancel,timeout,url,factory,WebTarget.YOUTUBE,LOOKUP);
 }
 static SSLContext context(String certificate)throws Exception{
  KeyStore store=KeyStore.getInstance("PKCS12");
  try(InputStream in=Files.newInputStream(Paths.get(certificate))){store.load(in,"fixture-only".toCharArray());}
  KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(store,"fixture-only".toCharArray());
  TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(store);
  SSLContext ctx=SSLContext.getInstance("TLS");ctx.init(km.getKeyManagers(),tm.getTrustManagers(),new SecureRandom());
  return ctx;
 }
 public static void main(String[] args)throws Exception{
  SSLContext ctx=context(args[0]);
  byte[] padding=new byte[180000];new Random(42).nextBytes(padding);String body="<!doctype html><html><title>YouTube</title><script>ytcfg.set({});</script><p>"+Base64.getEncoder().encodeToString(padding)+"</p></html>";html=body.getBytes(StandardCharsets.UTF_8);
  expect(ctx,"verified HTML threshold",normal(html),"THRESHOLD_REACHED",true);
  try(Fixture f=new Fixture(ctx,normal(html))){
   YoutubeProbe.Result r=YoutubeProbe.probe(f.server.getLocalPort(),new YoutubeProbe.Cancellation(),2000,
       "https://www.youtube.com/",ctx.getSocketFactory(),WebTarget.YOUTUBE,(h,c,d)->{throw new UnknownHostException("DoH unavailable");});
   check(!r.pass&&r.reason.equals("DNS_ERROR")&&r.http==0&&r.bytes==0,"DoH failure misclassified");
   check(r.modes.size()==1&&f.accepted.get()==0,"DoH failure reached the scan listener");
  }
  expect(ctx,"short complete HTML",normal("<html><title>YouTube</title>ytInitialData</html>".getBytes(StandardCharsets.UTF_8)),"SHORT_COMPLETE_RESPONSE",false);
  checkShortSitePages(ctx);
  expect(ctx,"HTTP 403",response(403,"Content-Length: 0",new byte[0]),"HTTP_STATUS",false);
  expect(ctx,"foreign redirect",response(302,"Location: https://consent.google.com/\r\nContent-Length: 0",new byte[0]),"UNEXPECTED_FINAL_DOMAIN",false);
  expect(ctx,"non-HTML",response(200,"Content-Type: application/octet-stream\r\nContent-Length: "+html.length,html),"NOT_HTML",false);
  byte[] captcha=("<html><title>YouTube</title>ytcfg.set<form action='https://x/sorry/'></form>"+body).getBytes(StandardCharsets.UTF_8);
  expect(ctx,"CAPTCHA",normal(captcha),"CAPTCHA_OR_CONSENT",false);
  expect(ctx,"wrong HTML",normal(new byte[150000]),"NOT_YOUTUBE_HTML",false);
  for(String encoding:new String[]{"gzip","deflate","raw-deflate"}){
   byte[] zipped=compressed(encoding.equals("gzip"),encoding.equals("raw-deflate"));check(zipped.length>102400,"compressed fixture threshold");
   expect(ctx,encoding,response(200,"Content-Type: text/html\r\nContent-Encoding: "+(encoding.equals("raw-deflate")?"deflate":encoding)+"\r\nContent-Length: "+zipped.length,zipped),"THRESHOLD_REACHED",true);
  }
  expect(ctx,"unsupported encoding",response(200,"Content-Type: text/html\r\nContent-Encoding: br\r\nContent-Length: "+html.length,html),"UNSUPPORTED_CONTENT_ENCODING",false);
  expect(ctx,"invalid gzip",response(200,"Content-Type: text/html\r\nContent-Encoding: gzip\r\nContent-Length: "+html.length,html),"INVALID_CONTENT_ENCODING",false);
  ByteArrayOutputStream chunked=new ByteArrayOutputStream();for(int n=0;n<html.length;n+=4000){int len=Math.min(4000,html.length-n);chunked.write((Integer.toHexString(len)+"\r\n").getBytes(StandardCharsets.US_ASCII));chunked.write(html,n,len);chunked.write(new byte[]{13,10});}chunked.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
  expect(ctx,"chunked HTML",response(206,"Content-Type: text/html\r\nTransfer-Encoding: chunked",chunked.toByteArray()),"THRESHOLD_REACHED",true);
  try(Fixture f=new Fixture(ctx,normal(html))){f.redirect=true;YoutubeProbe.Result r=measure(f.server.getLocalPort(),new YoutubeProbe.Cancellation(),2000,"https://www.youtube.com/",ctx.getSocketFactory());check(r.pass&&f.accepted.get()==2,"fresh connection per redirect");}
  try(Fixture f=new Fixture(ctx,normal(html))){f.firstClose=true;YoutubeProbe.Result r=measure(f.server.getLocalPort(),new YoutubeProbe.Cancellation(),2000,"https://www.youtube.com/",ctx.getSocketFactory());check(r.pass&&r.modes.size()==2&&r.mode.equals("TLS1.2"),"TLS1.2 fallback");}
  YoutubeProbe.Result untrusted=probe(ctx,normal(html),false);check(!untrusted.pass&&untrusted.reason.equals("CERTIFICATE_VERIFY_FAILED")&&untrusted.modes.size()==1,"untrusted certificate must be terminal: "+untrusted.json());
  try(Fixture f=new Fixture(ctx,normal(html))){YoutubeProbe.Result r=measure(f.server.getLocalPort(),new YoutubeProbe.Cancellation(),2000,"https://m.youtube.com/",ctx.getSocketFactory());check(!r.pass&&r.reason.equals("CERTIFICATE_VERIFY_FAILED"),"hostname verification");}
  try(Fixture f=new Fixture(ctx,normal(html))){f.stall=2000;long now=System.nanoTime();YoutubeProbe.Result r=measure(f.server.getLocalPort(),new YoutubeProbe.Cancellation(),350,"https://www.youtube.com/",ctx.getSocketFactory());check(!r.pass&&r.reason.equals("TIMEOUT")&&TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-now)<1000,"total deadline");}
  try(Fixture f=new Fixture(ctx,normal(html))){f.stall=2000;YoutubeProbe.Cancellation c=new YoutubeProbe.Cancellation();FutureTask<YoutubeProbe.Result> task=new FutureTask<>(()->measure(f.server.getLocalPort(),c,5000,"https://www.youtube.com/",ctx.getSocketFactory()));new Thread(task).start();Thread.sleep(100);c.cancel();check(task.get(700,TimeUnit.MILLISECONDS).reason.equals("CANCELLED"),"active socket cancellation");}
  checkOffThreadAbort();
  check(Arrays.equals(TcpIds.range("N1, T2–Т4, TCP3,484"),new int[]{1,2,3,4,484}),"range parser aliases/deduplication");
  for(String invalid:Arrays.asList("","0","485","3-1","1,","a","-1","1-2-3","T01;rm")){boolean rejected=false;try{TcpIds.range(invalid);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"bad range: "+invalid);}
  check(TcpStrategyScan.classify(2,2).equals("pass")&&TcpStrategyScan.classify(1,2).equals("unstable")&&TcpStrategyScan.classify(0,2).equals("fail")&&TcpStrategyScan.classify(1,1).equals("cancelled"),"classifications");
  AtomicInteger active=new AtomicInteger(),max=new AtomicInteger(),starts=new AtomicInteger(),stops=new AtomicInteger();
  TcpStrategyScan.Transport transport=new TcpStrategyScan.Transport(){public JSONObject start(int[] ns)throws Exception{starts.incrementAndGet();JSONObject ports=new JSONObject();for(int n:ns)ports.put("T"+n,n);return new JSONObject().put("youtube_ports",ports);}public void stop(){stops.incrementAndGet();}};
  check(TcpStrategyScan.YOUTUBE.reserve(),"reserve");check(!TcpStrategyScan.YOUTUBE.reserve(),"duplicate scan");
  TcpStrategyScan.YOUTUBE.run(TcpIds.range("1-30"),5,transport,()->true,(port,c)->{int a=active.incrementAndGet();max.accumulateAndGet(a,Math::max);try{Thread.sleep(15);}catch(InterruptedException e){Thread.currentThread().interrupt();}active.decrementAndGet();YoutubeProbe.Result r=new YoutubeProbe.Result();r.pass=true;r.reason="THRESHOLD_REACHED";return r;});
  JSONObject scan=new JSONObject(TcpStrategyScan.YOUTUBE.summary());check(scan.getString("ending").equals("limit_10")&&scan.getInt("checked")==10&&scan.getJSONArray("results").length()==10,"ten success cutoff");check(starts.get()==2&&stops.get()==2&&max.get()==5,"bounded concurrency and cleanup");TcpStrategyScan.YOUTUBE.release();
  check(TcpStrategyScan.YOUTUBE.reserve(),"reserve second");TcpStrategyScan.YOUTUBE.run(new int[]{1,2},1,transport,()->true,(port,c)->new YoutubeProbe.Result());check(TcpStrategyScan.YOUTUBE.display().equals(TcpStrategyScan.YOUTUBE.NONE)&&TcpStrategyScan.YOUTUBE.results.isEmpty(),"failure rows hidden");TcpStrategyScan.YOUTUBE.release();
  check(TcpStrategyScan.YOUTUBE.reserve(),"reserve DNS test");TcpStrategyScan.YOUTUBE.run(new int[]{1,2},1,transport,()->true,(port,c)->{YoutubeProbe.Result r=new YoutubeProbe.Result();r.reason="DNS_ERROR";return r;});check(new JSONObject(TcpStrategyScan.YOUTUBE.summary()).getInt("dns_errors")==6,"Scan DNS count missing");check(TcpStrategyScan.YOUTUBE.display().contains("DNS")&&!TcpStrategyScan.YOUTUBE.display().equals(TcpStrategyScan.YOUTUBE.NONE),"DNS failures displayed as a clean negative search");JSONObject refused=new JSONObject(TcpStrategyScan.YOUTUBE.summary());check(refused.getInt("dns_inconclusive")==2&&refused.getJSONArray("results").getJSONObject(0).getString("classification").equals("inconclusive_dns"),"DNS refusal became a strategy failure");TcpStrategyScan.YOUTUBE.release();
  AtomicInteger outcomes=new AtomicInteger();
  check(TcpStrategyScan.YOUTUBE.reserve(),"reserve DNS replacement");
  TcpStrategyScan.YOUTUBE.run(new int[]{1},1,transport,()->true,(port,c)->{
   YoutubeProbe.Result r=new YoutubeProbe.Result();r.pass=outcomes.incrementAndGet()!=1;r.reason=r.pass?"PASS":"DNS_ERROR";return r;
  });
  JSONObject retry=new JSONObject(TcpStrategyScan.YOUTUBE.summary()).getJSONArray("results").getJSONObject(0);
  check(outcomes.get()==3&&retry.getString("classification").equals("pass")&&retry.getInt("valid_attempts")==2,"DNS replacement failed to produce two usable measurements");
  TcpStrategyScan.YOUTUBE.release();
  outcomes.set(0);check(TcpStrategyScan.YOUTUBE.reserve(),"reserve partial DNS success");
  TcpStrategyScan.YOUTUBE.run(new int[]{1},1,transport,()->true,(port,c)->{
   YoutubeProbe.Result r=new YoutubeProbe.Result();r.pass=outcomes.incrementAndGet()==1;r.reason=r.pass?"PASS":"DNS_ERROR";return r;
  });
  JSONObject partial=new JSONObject(TcpStrategyScan.YOUTUBE.summary()).getJSONArray("results").getJSONObject(0);
  check(partial.getString("classification").equals("one_pass_dns")&&TcpStrategyScan.YOUTUBE.display().contains("pass один раз, DNS"),"One accepted page plus DNS errors became a strategy failure");
  TcpStrategyScan.YOUTUBE.release();

  check(TcpStrategyScan.YOUTUBE.reserve(),"reserve cancel");Thread scanThread=new Thread(()->TcpStrategyScan.YOUTUBE.run(TcpIds.range("1-30"),3,transport,()->true,(port,c)->{while(!c.stopped())try{Thread.sleep(10);}catch(InterruptedException e){Thread.currentThread().interrupt();}YoutubeProbe.Result r=new YoutubeProbe.Result();r.reason="CANCELLED";return r;}));scanThread.start();Thread.sleep(80);TcpStrategyScan.YOUTUBE.cancel();scanThread.join(1000);check(!scanThread.isAlive()&&new JSONObject(TcpStrategyScan.YOUTUBE.summary()).getString("ending").equals("cancelled"),"scan cancellation");TcpStrategyScan.YOUTUBE.release();
  check(!YoutubeProbe.allowed(java.net.URI.create("http://www.youtube.com/"))&&!YoutubeProbe.allowed(java.net.URI.create("https://www.youtube.com.evil/"))&&!YoutubeProbe.allowed(java.net.URI.create("https://user@www.youtube.com/")),"redirect allowlist");
  System.out.println(new JSONObject().put("passed",true).put("checks",checks).put("network_cases",results).put("max_parallel",max.get()).put("limit",scan));
 }

 private static void checkShortSitePages(SSLContext ctx)throws Exception{
  String contentType="Content-Type: text/html";
  byte[] complete=("<html><title>X</title><script src='https://abs.twimg.com/script.js'></script>"
      +"x".repeat(45775)+"</html>").getBytes(StandardCharsets.UTF_8);
  expectX(ctx,"complete X Content-Length",normal(complete),"COMPLETE_HTML",true);
  expectX(ctx,"truncated X Content-Length with closing HTML tag",
      response(200,contentType+"\r\nContent-Length: "+(complete.length+10),complete),"TRANSFER_ERROR",false);
  expectX(ctx,"complete X with connection close",response(200,contentType,complete),"COMPLETE_HTML",true);
  expectX(ctx,"truncated X with connection close",
      response(200,contentType,Arrays.copyOf(complete,complete.length-7)),"SHORT_COMPLETE_RESPONSE",false);
  expectX(ctx,"partial X HTTP 206",
      response(206,contentType+"\r\nContent-Length: "+complete.length,complete),"SHORT_COMPLETE_RESPONSE",false);
  expectX(ctx,"unrelated short HTML",normal("<html><title>Another site</title></html>".getBytes(StandardCharsets.UTF_8)),"NOT_X_HTML",false);
  byte[] body=(Integer.toHexString(complete.length)+"\r\n"+new String(complete,StandardCharsets.UTF_8)+"\r\n").getBytes(StandardCharsets.UTF_8);
  ByteArrayOutputStream framed=new ByteArrayOutputStream();
  framed.write(body);
  framed.write("0\r\nX-Fixture: complete\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
  String chunked=contentType+"\r\nTransfer-Encoding: chunked";
  expectX(ctx,"complete chunked X",response(200,chunked,framed.toByteArray()),"COMPLETE_HTML",true);
  expectX(ctx,"incomplete chunked X",response(200,chunked,body),"TRANSFER_ERROR",false);
  ByteArrayOutputStream compressed=new ByteArrayOutputStream();
  try(OutputStream gzip=new GZIPOutputStream(compressed)){gzip.write(complete);}
  byte[] withoutTrailer=Arrays.copyOf(compressed.toByteArray(),compressed.size()-8);
  expectX(ctx,"truncated gzip inside a complete X HTTP frame",
      response(200,contentType+"\r\nContent-Encoding: gzip\r\nContent-Length: "+withoutTrailer.length,withoutTrailer),
      "SHORT_COMPLETE_RESPONSE",false);
 }

 private static void expectX(SSLContext ctx,String name,byte[] response,String reason,boolean pass)throws Exception{
  try(Fixture fixture=new Fixture(ctx,response)){
   YoutubeProbe.Result result=YoutubeProbe.probe(fixture.server.getLocalPort(),new YoutubeProbe.Cancellation(),2500,
       WebTarget.X.url,ctx.getSocketFactory(),WebTarget.X,LOOKUP);
   check(result.pass==pass&&result.reason.equals(reason),name+": "+result.json());
   results.put(new JSONObject().put("name",name).put("result",result.json()));
  }
 }

 private static void checkOffThreadAbort()throws Exception{
  YoutubeProbe.Cancellation cancellation=new YoutubeProbe.Cancellation();
  Thread caller=Thread.currentThread();
  AtomicReference<Thread> closedOn=new AtomicReference<>();
  CountDownLatch closed=new CountDownLatch(1);
  cancellation.attach(()->{closedOn.set(Thread.currentThread());closed.countDown();});
  cancellation.cancel();
  check(cancellation.stopped()&&closed.await(1,TimeUnit.SECONDS)&&closedOn.get()!=caller,
      "Stop closed a transport on the service caller thread");
  CountDownLatch lateClosed=new CountDownLatch(1);
  boolean refused=false;
  try{cancellation.attach(lateClosed::countDown);}catch(InterruptedIOException expected){refused=true;}
  check(refused&&lateClosed.await(1,TimeUnit.SECONDS),"Transport racing with Stop was not aborted");
 }
}
