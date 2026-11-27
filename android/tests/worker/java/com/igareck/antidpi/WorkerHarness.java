/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Exercises the production channel and adapter on real loopback sockets. */
public final class WorkerHarness {
 static int checks;
 static void check(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
 static JSONObject descriptor(WorkerChannel.Server s,String token,String epoch)throws Exception{return new JSONObject().put("protocol",18).put("port",s.port()).put("capability",token).put("epoch",epoch);}
 static void reject(WorkerChannel.Client c,String message)throws Exception{boolean failed=false;try{c.call("status",new JSONObject(),1000);}catch(Exception expected){failed=true;}check(failed,message);}
 static void await(Callable<Boolean> condition)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition.call()){if(System.nanoTime()>until)throw new AssertionError("Deadline");Thread.sleep(10);}}
 public static void main(String[] args)throws Exception{
  String token=WorkerChannel.token(),epoch=UUID.randomUUID().toString();AtomicInteger calls=new AtomicInteger(),lost=new AtomicInteger();
  String instance=UUID.randomUUID().toString();AtomicBoolean alive=new AtomicBoolean(true);
  WorkerChannel.Endpoint endpoint=new WorkerChannel.Endpoint(){
   public String call(String op,JSONObject a)throws Exception{
    calls.incrementAndGet();switch(op){
     case "uid":return "2000";
     case "logs":return "[]";
     case "bulk":return "\u0001".repeat(80000);
     case "stop":if(a.getString("instance").equals(instance))alive.set(false);break;
     case "start":alive.set(true);break;
     case "status":break;
     default:throw new SecurityException("Unsupported operation");
    }
    return new JSONObject().put("instance",instance).put("service_epoch",epoch).put("state",alive.get()?"RUNNING":"STOPPED").put("native_alive",alive.get()).put("workspace_cleanup_ok",!alive.get()).put("tap",new JSONObject().put("cleanup_ok",!alive.get())).toString();
   }
   public void ownerLost(){lost.incrementAndGet();alive.set(false);}
  };
  try(WorkerChannel.Server server=new WorkerChannel.Server(token,epoch,endpoint,5000)){
   JSONObject d=descriptor(server,token,epoch);WorkerChannel.Client client=new WorkerChannel.Client(d);
   check(new JSONObject(client.call("status",new JSONObject(),1000)).getBoolean("native_alive"),"Real worker status missing");
   int before=calls.get();reject(new WorkerChannel.Client(descriptor(server,WorkerChannel.token(),epoch)),"Wrong capability accepted");check(calls.get()==before,"Unauthenticated request reached engine");
   reject(new WorkerChannel.Client(descriptor(server,token,UUID.randomUUID().toString())),"Wrong epoch accepted");
   String nonce=UUID.randomUUID().toString();JSONObject request=new JSONObject().put("protocol",18).put("epoch",epoch).put("nonce",nonce).put("operation","status").put("arguments","{}").put("auth",WorkerChannel.requestMac(token,epoch,nonce,"status","{}"));
   for(int i=0;i<2;i++)try(Socket socket=new Socket("127.0.0.1",server.port())){socket.setSoTimeout(1000);WorkerChannel.write(socket.getOutputStream(),request.toString(),WorkerChannel.MAX_REQUEST);boolean got=false;try{WorkerChannel.line(socket.getInputStream(),WorkerChannel.MAX_RESPONSE);got=true;}catch(EOFException expected){}check(got==(i==0),"Nonce replay accepted or valid first receipt rejected");}
   try(Socket socket=new Socket("127.0.0.1",server.port())){socket.setSoTimeout(1500);byte[] huge=new byte[WorkerChannel.MAX_REQUEST+2];Arrays.fill(huge,(byte)'A');socket.getOutputStream().write(huge);boolean rejected=false;try{rejected=socket.getInputStream().read()==-1;}catch(IOException expected){rejected=true;}check(rejected,"Oversized frame accepted");}
   int prior=calls.get();client.call("heartbeat",new JSONObject(),1000);check(calls.get()==prior,"Heartbeat mutated engine");
   check(client.call("bulk",new JSONObject(),3000).length()==80000,"Buffered large/escaped log response failed");
   try(Socket slow=new Socket("127.0.0.1",server.port())){slow.setSoTimeout(2500);slow.getOutputStream().write('{');check(slow.getInputStream().read()==-1,"Partial unauthenticated frame exceeded total read deadline");}
   // In the real app this adapter survives the separate Shizuku launcher's death.
   PacketWorkerTransport transport=new PacketWorkerTransport(d);
   check(transport.getUid()==2000&&new JSONObject(transport.status()).getString("state").equals("RUNNING"),"Autonomous adapter status failed");
   check(new JSONObject(transport.stopProxy(UUID.randomUUID().toString())).getBoolean("native_alive"),"Stale stop killed current instance");
   JSONObject stopped=new JSONObject(transport.stopProxy(instance));check(!stopped.getBoolean("native_alive")&&stopped.getJSONObject("tap").getBoolean("cleanup_ok")&&stopped.getBoolean("workspace_cleanup_ok"),"Stop failed actual receipt contract");
   check(new JSONObject(transport.stopProxy(instance)).getString("state").equals("STOPPED"),"Repeated stop failed");
   transport.detach();
  }
  // Owner lease is independent of Shizuku. An invalid request cannot keep a lost app alive.
  alive.set(true);lost.set(0);try(WorkerChannel.Server server=new WorkerChannel.Server(token,epoch,endpoint,250)){
   Thread noise=new Thread(()->{for(int i=0;i<10;i++)try{rejectNoCount(new WorkerChannel.Client(descriptor(server,WorkerChannel.token(),epoch)));Thread.sleep(20);}catch(Exception ignored){}});noise.start();await(()->lost.get()==1);noise.join();check(!alive.get(),"Lost owner did not stop engine");
  }
  check(lost.get()==1,"Owner loss ran more than once");
  // Production retained mode cleans packet resources but preserves the private
  // activation across an app freeze/reopen, without a second ADB bootstrap.
  alive.set(true);lost.set(0);
  try(WorkerChannel.Server server=new WorkerChannel.Server(token,epoch,endpoint,150,true)){
   WorkerChannel.Client client=new WorkerChannel.Client(descriptor(server,token,epoch));
   client.call("retain",new JSONObject(),1000);
   check(new JSONObject(client.call("status",new JSONObject(),1000)).getBoolean("native_alive"),"Retained mode did not start active");
   await(()->lost.get()==1);check(!alive.get(),"Retained activation left orphan native resources");
   Thread.sleep(320);check(lost.get()==1,"Idle retained worker repeatedly cleaned the same owner");
   JSONObject resumed=new JSONObject(client.call("status",new JSONObject(),1000));check(!resumed.getBoolean("native_alive")&&resumed.getString("state").equals("STOPPED"),"Reopen failed to reattach to cleaned retained worker");
   check(new JSONObject(client.call("start",new JSONObject(),1000)).getBoolean("native_alive"),"Reattached worker could not start another strategy");
   await(()->lost.get()==2);check(!alive.get(),"Second lost owner was not cleaned");
   reject(new WorkerChannel.Client(descriptor(server,WorkerChannel.token(),epoch)),"Paused retained worker accepted wrong capability");
   check(lost.get()==2,"Wrong capability revived owner lease");
   check(!new JSONObject(client.call("status",new JSONObject(),1000)).getBoolean("native_alive"),"Authenticated second reattach failed");
  }
  // A cancelled USB/ADB bootstrap, or a failed private-descriptor commit, must
  // not create an unclaimed indefinitely retained shell process.
  lost.set(0);try(WorkerChannel.Server server=new WorkerChannel.Server(token,epoch,endpoint,100,true)){
   WorkerChannel.Client client=new WorkerChannel.Client(descriptor(server,token,epoch));client.call("uid",new JSONObject(),1000);
   await(()->lost.get()==1);reject(client,"Uncommitted bootstrap retained its channel");
  }
  // A user/session reconnect cannot enter a new native start while the old owner's
  // cancellation and TAP cleanup are still being acknowledged.
  CountDownLatch cleaning=new CountDownLatch(1),cleaned=new CountDownLatch(1);AtomicInteger entered=new AtomicInteger();
  WorkerChannel.Endpoint slowCleanup=new WorkerChannel.Endpoint(){public String call(String op,JSONObject a){entered.incrementAndGet();return "{}";}public void ownerLost(){cleaning.countDown();try{cleaned.await(2,TimeUnit.SECONDS);}catch(InterruptedException ignored){}}};
  try(WorkerChannel.Server server=new WorkerChannel.Server(token,epoch,slowCleanup,100,true)){
   new WorkerChannel.Client(descriptor(server,token,epoch)).call("retain",new JSONObject(),1000);
   check(cleaning.await(2,TimeUnit.SECONDS),"Retained cleanup did not begin");
   WorkerChannel.Client client=new WorkerChannel.Client(descriptor(server,token,epoch));reject(client,"New command entered while old owner cleanup pending");check(entered.get()==0,"Cleanup race admitted native mutation");
   cleaned.countDown();Thread.sleep(50);client.call("status",new JSONObject(),1000);check(entered.get()==1,"Worker did not resume after verified cleanup");
  }
  // A different app reusing an expired worker's port learns no secret and cannot forge cleanup.
  try(ServerSocket foreign=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
   AtomicBoolean exposed=new AtomicBoolean();Thread imposter=new Thread(()->{try(Socket socket=foreign.accept()){
    String raw=WorkerChannel.line(WorkerChannel.reader(socket,1000),WorkerChannel.MAX_REQUEST);exposed.set(raw.contains(token));JSONObject q=new JSONObject(raw);
    String fake=new JSONObject().put("protocol",18).put("epoch",epoch).put("nonce",q.getString("nonce")).put("ok",true).put("payload","2000").put("auth","0".repeat(64)).toString();WorkerChannel.write(socket.getOutputStream(),fake,WorkerChannel.MAX_RESPONSE);
   }catch(Exception e){throw new RuntimeException(e);}});imposter.start();
   WorkerChannel.Client reused=new WorkerChannel.Client(new JSONObject().put("protocol",18).put("port",foreign.getLocalPort()).put("capability",token).put("epoch",epoch));reject(reused,"Forged worker response accepted");imposter.join();check(!exposed.get(),"Stale port peer learned private capability");
  }
  boolean bounded=false;try{WorkerChannel.line(new ByteArrayInputStream(new byte[WorkerChannel.MAX_REQUEST+1]),WorkerChannel.MAX_REQUEST);}catch(IOException expected){bounded=true;}check(bounded,"Unterminated input not bounded");
  JSONObject invalid=new JSONObject().put("protocol",15).put("port",12345).put("capability",token).put("epoch",epoch);boolean protocol=false;try{new WorkerChannel.Client(invalid);}catch(IOException expected){protocol=true;}check(protocol,"Old protocol silently accepted");
  System.out.println(new JSONObject().put("passed",true).put("checks",checks).put("real_loopback_sockets",true).put("android_bootstrap_tested",false));
 }
 static void rejectNoCount(WorkerChannel.Client c){try{c.call("status",new JSONObject(),500);}catch(Exception expected){}}
}
