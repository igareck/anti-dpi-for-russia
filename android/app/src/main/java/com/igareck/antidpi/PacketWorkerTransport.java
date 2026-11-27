/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import android.os.*;
import java.net.ConnectException;
import java.util.concurrent.*;
import org.json.*;

/** ITransportService adapter; bootstrap uses embedded ADB. */
final class PacketWorkerTransport extends ITransportService.Stub {
 private final WorkerChannel.Client client;
 private final ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"packet-owner-heartbeat");t.setDaemon(true);return t;});
 private static final long STALE_NS=TimeUnit.SECONDS.toNanos(6);
 private final java.util.concurrent.atomic.AtomicBoolean reportedLoss=new java.util.concurrent.atomic.AtomicBoolean();
 private volatile long lastReply=System.nanoTime();
 private volatile boolean detached,connectionRefused;
 PacketWorkerTransport(JSONObject descriptor)throws Exception{
  client=new WorkerChannel.Client(descriptor);
  if(Integer.parseInt(client.call("uid",new JSONObject(),2000))!=2000)throw new SecurityException("Worker UID mismatch");
  heartbeat.scheduleWithFixedDelay(()->{
   if(detached)return;
   try{client.call("heartbeat",new JSONObject(),1500);replyArrived();}
   catch(Exception failure){callFailed(failure);}
  },0,2,TimeUnit.SECONDS);
 }
 private void replyArrived(){
  lastReply=System.nanoTime();
  connectionRefused=false;
  if(!detached&&reportedLoss.getAndSet(false))PacketBridge.linkRestored(this);
 }
 private void callFailed(Exception failure){
  if(failure instanceof ConnectException)connectionRefused=true;
  if(!detached&&!available()&&reportedLoss.compareAndSet(false,true))
   PacketBridge.linkLost(this,failure.getClass().getSimpleName());
 }
 boolean available(){return !detached&&!connectionRefused&&System.nanoTime()-lastReply<STALE_NS;}
 void confirmRetention()throws RemoteException{call("retain",new JSONObject(),2500);}
 void shutdown()throws RemoteException{call("shutdown",new JSONObject(),5000);}
 void detach(){detached=true;heartbeat.shutdownNow();}
 private String call(String op,JSONObject a,int timeout)throws RemoteException{
  try{String result=client.call(op,a,timeout);replyArrived();return result;}
  catch(Exception e){
   callFailed(e);
   RemoteException failure=new RemoteException("Локальный сетевой модуль: "+e.getClass().getSimpleName()+": "+e.getMessage());
   failure.initCause(e);throw failure;
  }
 }
 private static JSONObject args(){return new JSONObject();}
 private static JSONObject put(JSONObject a,String key,Object value){try{return a.put(key,value);}catch(JSONException e){throw new IllegalArgumentException(e);}}
 @Override public int getUid()throws RemoteException{return Integer.parseInt(call("uid",args(),2000));}
 @Override public String status()throws RemoteException{return call("status",args(),2500);}
 @Override public String drainLogs()throws RemoteException{return call("logs",args(),3000);}
 @Override public String snapshotMetrics()throws RemoteException{return call("metrics",args(),4000);}
 @Override public String stopProxy(String id)throws RemoteException{return call("stop",put(args(),"instance",id),15000);}
 @Override public String recoverProxy(String id,String epoch)throws RemoteException{return call("recover",put(put(args(),"instance",id),"epoch",epoch),15000);}
 @Override public String startProxy(String p,int port,boolean verbose,IBinder owner,String id)throws RemoteException{return startProxyUdpDiscord(p,port,verbose,owner,id,false,"Q1","D0");}
 @Override public String startProxyUdp(String p,int port,boolean verbose,IBinder owner,String id,boolean udp,String quic)throws RemoteException{return startProxyUdpDiscord(p,port,verbose,owner,id,udp,quic,"D0");}
 @Override public String startProxyUdpDiscord(String p,int port,boolean verbose,IBinder owner,String id,boolean udp,String quic,String discord)throws RemoteException{
  JSONObject a=args();put(a,"profile",p);put(a,"port",port);put(a,"verbose",verbose);put(a,"instance",id);put(a,"udp",udp);put(a,"quic",quic);put(a,"discord",discord);return call("start",a,70000);
 }
 @Override public String startYoutube(int[] numbers,IBinder owner,String id)throws RemoteException{JSONArray n=new JSONArray();for(int x:numbers)n.put(x);return call("scan",put(put(args(),"numbers",n),"instance",id),70000);}
 @Override public void destroy(){detach();}
}
