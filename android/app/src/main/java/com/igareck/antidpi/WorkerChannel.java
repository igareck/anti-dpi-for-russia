/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.*;

/** App-private capability transport. No shell commands or arbitrary destinations. */
final class WorkerChannel {
 static final int PROTOCOL=18,MAX_REQUEST=16384,MAX_RESPONSE=1048576;
 static final long LEASE_MS=30000;
 static final String ADDRESS="127.0.0.1";
 static String token(){byte[] b=new byte[32];new SecureRandom().nextBytes(b);StringBuilder s=new StringBuilder();for(byte n:b)s.append(String.format(Locale.ROOT,"%02x",n&255));return s.toString();}
 static void validateToken(String token)throws IOException{if(token==null||!token.matches("[0-9a-f]{64}"))throw new IOException("Invalid worker capability");}
 static String mac(String token,String text)throws Exception{
  Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.US_ASCII),"HmacSHA256"));
  byte[] digest=mac.doFinal(text.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:digest)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();
 }
 static boolean equal(String expected,String actual){return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),actual.getBytes(StandardCharsets.US_ASCII));}
 static String requestMac(String token,String epoch,String nonce,String op,String body)throws Exception{return mac(token,"request\n"+PROTOCOL+"\n"+epoch+"\n"+nonce+"\n"+op+"\n"+body);}
 static String responseMac(String token,String epoch,String nonce,boolean ok,String payload)throws Exception{return mac(token,"response\n"+PROTOCOL+"\n"+epoch+"\n"+nonce+"\n"+ok+"\n"+payload);}
 static String line(InputStream in,int maximum)throws IOException{
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();int b;
  while((b=in.read())!=-1){if(b=='\n')return new String(bytes.toByteArray(),StandardCharsets.UTF_8);if(bytes.size()>=maximum)throw new IOException("Worker frame too large");bytes.write(b);}
  throw new EOFException("Incomplete worker frame");
 }
 static void write(OutputStream out,String text,int maximum)throws IOException{
  byte[] bytes=text.getBytes(StandardCharsets.UTF_8);if(bytes.length>maximum||text.indexOf('\n')>=0)throw new IOException("Invalid worker frame");out.write(bytes);out.write('\n');out.flush();
 }
 static InputStream reader(Socket socket,int timeout)throws IOException{
  final InputStream raw=socket.getInputStream();final long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);
  return new BufferedInputStream(new FilterInputStream(raw){
   private void remaining()throws IOException{long ns=deadline-System.nanoTime();if(ns<=0)throw new SocketTimeoutException("Worker frame deadline");socket.setSoTimeout((int)Math.max(1,TimeUnit.NANOSECONDS.toMillis(ns)));}
   @Override public int read()throws IOException{remaining();return raw.read();}
   @Override public int read(byte[] b,int offset,int length)throws IOException{remaining();return raw.read(b,offset,length);}
  },8192);
 }
 interface Endpoint {String call(String operation,JSONObject arguments)throws Exception;void ownerLost();default void abandoned(){ownerLost();}}
 static final class Server implements Closeable {
  private final ServerSocket listener;
  private final Endpoint endpoint;
  private final String token,epoch;
  private final long leaseMillis;
  private final boolean retainOnOwnerLoss;
  private volatile long lastSeen=System.nanoTime();
  private volatile boolean closed;
  private volatile boolean paused,cleaning,retentionConfirmed;
  private final Object leaseLock=new Object();
  private final LinkedHashSet<String> nonces=new LinkedHashSet<>();
  private final ThreadPoolExecutor pool=new ThreadPoolExecutor(6,6,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(16),r->{Thread t=new Thread(r,"packet-rpc");t.setDaemon(true);return t;});
  Server(String capability,String epoch,Endpoint endpoint)throws Exception{this(capability,epoch,endpoint,LEASE_MS);}
  Server(String capability,String epoch,Endpoint endpoint,long leaseMillis)throws Exception{
   this(capability,epoch,endpoint,leaseMillis,false);
  }
  Server(String capability,String epoch,Endpoint endpoint,long leaseMillis,boolean retainOnOwnerLoss)throws Exception{
   validateToken(capability);UUID.fromString(epoch);this.token=capability;this.epoch=epoch;this.endpoint=endpoint;this.leaseMillis=leaseMillis;this.retainOnOwnerLoss=retainOnOwnerLoss;
   listener=new ServerSocket();listener.bind(new InetSocketAddress(InetAddress.getByName(ADDRESS),0),16);
   Thread accept=new Thread(()->accept(),"packet-rpc-accept");accept.setDaemon(true);accept.start();
   Thread lease=new Thread(()->{
    while(!closed){try{Thread.sleep(Math.min(500,Math.max(10,leaseMillis/4)));}catch(InterruptedException e){return;}
     boolean expired=false,retain=false;synchronized(leaseLock){if(!closed&&!paused&&TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-lastSeen)>leaseMillis){expired=true;paused=true;cleaning=true;retain=retainOnOwnerLoss&&retentionConfirmed;if(!retain)closed=true;}}
     if(expired){
      if(!retain){try{listener.close();}catch(IOException ignored){}pool.shutdownNow();if(retainOnOwnerLoss)endpoint.abandoned();else endpoint.ownerLost();return;}
      // The proxy must be cleaned when its app disappears. The authenticated idle
      // launcher can remain available, like Shizuku, without an ADB connection.
      try{endpoint.ownerLost();}finally{synchronized(leaseLock){cleaning=false;}}
     }
    }
   },"packet-app-lease");lease.setDaemon(true);lease.start();
  }
  int port(){return listener.getLocalPort();}
  private void accept(){while(!closed)try{
   Socket socket=listener.accept();socket.setSoTimeout(1500);
   try{pool.execute(()->serve(socket));}catch(RejectedExecutionException full){socket.close();}
  }catch(IOException e){if(!closed)close();}}
  private void serve(Socket socket){try(Socket own=socket){
   if(!own.getInetAddress().isLoopbackAddress())return;
   JSONObject request=new JSONObject(line(reader(own,1500),MAX_REQUEST));String nonce=request.optString("nonce"),op=request.optString("operation"),body=request.optString("arguments");
   // A failed credential never extends the owner's lease, and no credential is logged.
   if(!nonce.matches("[0-9a-f-]{36}")||!op.matches("[a-z_]{1,16}")
    ||!UUID.fromString(nonce).toString().equals(nonce)||request.optInt("protocol")!=PROTOCOL||!epoch.equals(request.optString("epoch"))
    ||!equal(requestMac(token,epoch,nonce,op,body),request.optString("auth")))return;
   synchronized(leaseLock){if(closed||cleaning||nonces.contains(nonce))return;nonces.add(nonce);if(nonces.size()>256)nonces.remove(nonces.iterator().next());lastSeen=System.nanoTime();paused=false;}
   JSONObject response=new JSONObject().put("protocol",PROTOCOL).put("epoch",epoch).put("nonce",nonce);
   try{String payload;if(op.equals("heartbeat"))payload="{}";else if(op.equals("retain")){synchronized(leaseLock){retentionConfirmed=true;}payload="{}";}else payload=endpoint.call(op,new JSONObject(body));response.put("ok",true).put("payload",payload);}
   catch(Exception e){response.put("ok",false).put("error",e.getClass().getSimpleName()+": "+e.getMessage());}
   boolean ok=response.getBoolean("ok");response.put("auth",responseMac(token,epoch,nonce,ok,response.getString(ok?"payload":"error")));
   write(own.getOutputStream(),response.toString(),MAX_RESPONSE);
  }catch(Exception ignored){/* Authentication/frame failures deliberately have no response. */}}
  public void close(){synchronized(leaseLock){closed=true;}try{listener.close();}catch(IOException ignored){}pool.shutdownNow();}
 }
 static final class Client {
  final int port;final String capability,epoch;
  Client(JSONObject descriptor)throws Exception{
   if(descriptor.getInt("protocol")!=PROTOCOL)throw new IOException("Worker protocol mismatch");
   port=descriptor.getInt("port");if(port<1024||port>65535)throw new IOException("Invalid worker port");
   capability=descriptor.getString("capability");validateToken(capability);epoch=UUID.fromString(descriptor.getString("epoch")).toString();
  }
  String call(String operation,JSONObject arguments,int timeout)throws Exception{
   String nonce=UUID.randomUUID().toString(),body=arguments.toString();JSONObject request=new JSONObject().put("protocol",PROTOCOL).put("epoch",epoch).put("nonce",nonce).put("operation",operation).put("arguments",body).put("auth",requestMac(capability,epoch,nonce,operation,body));
   // Only retry the TCP dial. Once a request may have reached the worker,
   // repeating a start, stop or cleanup would be unsafe.
   try(Socket socket=connect()){
    socket.setSoTimeout(timeout);
    write(socket.getOutputStream(),request.toString(),MAX_REQUEST);
    JSONObject response=new JSONObject(line(reader(socket,timeout),MAX_RESPONSE));
    if(response.getInt("protocol")!=PROTOCOL||!epoch.equals(response.getString("epoch"))||!nonce.equals(response.getString("nonce")))throw new IOException("Worker receipt mismatch");
    boolean ok=response.getBoolean("ok");String payload=response.getString(ok?"payload":"error");
    if(!equal(responseMac(capability,epoch,nonce,ok,payload),response.optString("auth")))throw new IOException("Worker receipt authentication failed");
    if(!ok)throw new IOException(payload);return payload;
   }
  }
  private Socket connect()throws IOException,InterruptedException{
   InetSocketAddress address=new InetSocketAddress(InetAddress.getByName(ADDRESS),port);
   try{return dial(address,700);}
   catch(SocketTimeoutException e){
    Thread.sleep(120);
    return dial(address,1400);
   }
  }
  private static Socket dial(InetSocketAddress address,int timeout)throws IOException{
   Socket socket=new Socket();
   try{socket.connect(address,timeout);return socket;}
   catch(IOException|RuntimeException e){try{socket.close();}catch(IOException ignored){}throw e;}
  }
 }
 private WorkerChannel(){}
}
