/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import android.content.Context;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.nsd.*;
import android.os.*;
import java.net.*;
import java.util.*;
import org.json.JSONObject;

/** Discover this device; keep pairing and connection records independent. */
final class AdbDiscovery {
 private final Handler main=new Handler(Looper.getMainLooper());
 private final NsdManager manager;
 private final java.util.concurrent.Executor executor;
 private final ArrayList<NsdManager.DiscoveryListener> listeners=new ArrayList<>();
 private final AdbDiscoveryState state=new AdbDiscoveryState();
 private final ArrayDeque<Job> pending=new ArrayDeque<>();
 private volatile boolean running,unscoped;
 private long session;
 private Job resolving;
 private int foundCount,lostCount,rejectedCount,failedCount,timedOut;
 private volatile String diagnostic="{}";
 volatile int pairingPort,connectPort;
 private static final class Job {
  final NsdServiceInfo info;final AdbDiscoveryState.Ticket ticket;NsdManager.ResolveListener listener;Runnable timeout;
  Job(NsdServiceInfo info,AdbDiscoveryState.Ticket ticket){this.info=info;this.ticket=ticket;}
 }
 AdbDiscovery(Context context){manager=(NsdManager)context.getSystemService(Context.NSD_SERVICE);executor=context.getMainExecutor();}
 boolean running(){return running;}
 String snapshot(){return diagnostic;}
 static boolean local(InetAddress address){try{
  if(address==null)return false;if(address.isLoopbackAddress()||NetworkInterface.getByInetAddress(address)!=null)return true;
  Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
  while(interfaces!=null&&interfaces.hasMoreElements())for(InetAddress own:Collections.list(interfaces.nextElement().getInetAddresses()))if(Arrays.equals(own.getAddress(),address.getAddress()))return true;
 }catch(Exception ignored){}return false;}
 private static boolean own(NsdServiceInfo info){
  if(Build.VERSION.SDK_INT>=34)for(InetAddress a:info.getHostAddresses())if(local(a))return true;
  return local(info.getHost());
 }
 private static String network(NsdServiceInfo info){return Build.VERSION.SDK_INT>=33&&info.getNetwork()!=null?info.getNetwork().toString():"";}
 private void publish(){
  pairingPort=state.port(true);connectPort=state.port(false);
  try{diagnostic=new JSONObject().put("running",running).put("found",foundCount).put("lost",lostCount).put("resolve_failed",failedCount).put("resolve_timeout",timedOut).put("nonlocal_or_invalid",rejectedCount).put("pairing_port",pairingPort).put("connect_port",connectPort).put("physical_network_scope",Build.VERSION.SDK_INT>=33&&!unscoped).put("unscoped_retry",unscoped).toString();}catch(Exception ignored){}
 }
 void start(){start(false);}
 void refresh(){stop();start(true);}
 void start(boolean fallback){main.post(()->{
  if(running)return;running=true;unscoped=fallback;final long own=++session;publish();
  for(boolean isPairing:new boolean[]{true,false}){
   final boolean pairing=isPairing;String type=pairing?"_adb-tls-pairing._tcp.":"_adb-tls-connect._tcp.";
   NsdManager.DiscoveryListener listener=new NsdManager.DiscoveryListener(){
    public void onDiscoveryStarted(String s){AppLog.line("ADB_DISCOVERY","Started role="+(pairing?"pairing":"connect"));}
    public void onDiscoveryStopped(String s){}
    public void onStartDiscoveryFailed(String s,int code){AppLog.line("ADB_DISCOVERY","Start failed role="+(pairing?"pairing":"connect")+" code="+code);}
    public void onStopDiscoveryFailed(String s,int code){}
    public void onServiceFound(NsdServiceInfo info){main.post(()->{
     if(!running||session!=own||pending.size()>=32)return;
     AdbDiscoveryState.Ticket ticket=state.found(pairing,info.getServiceName(),network(info));if(ticket==null)return;
     foundCount++;pending.addLast(new Job(info,ticket));publish();resolveNext();
    });}
    public void onServiceLost(NsdServiceInfo info){main.post(()->{
     if(!running||session!=own)return;
     state.lost(pairing,info.getServiceName(),network(info));lostCount++;publish();
     AppLog.line("ADB_DISCOVERY","Lost role="+(pairing?"pairing":"connect")+"; connect_port="+connectPort);
    });}
   };
   listeners.add(listener);try{
    if(Build.VERSION.SDK_INT>=33&&!fallback){
     NetworkRequest physical=new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build();
     manager.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,physical,executor,listener);
    }else manager.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,listener);
   }catch(Exception e){AppLog.line("ADB_DISCOVERY","Automatic discovery unavailable; manual ports remain available");}
  }
 });}
 private void finish(Job job){if(resolving!=job)return;main.removeCallbacks(job.timeout);resolving=null;publish();resolveNext();}
 private void resolveNext(){
  if(!running||resolving!=null)return;
  while(!pending.isEmpty()&&!state.current(pending.peekFirst().ticket))pending.removeFirst();
  if(pending.isEmpty())return;
  final long own=session;final Job job=pending.removeFirst();resolving=job;
  job.listener=new NsdManager.ResolveListener(){
   public void onResolveFailed(NsdServiceInfo info,int code){main.post(()->{
    if(session!=own||resolving!=job)return;failedCount++;AppLog.line("ADB_DISCOVERY","Resolve failed role="+(job.ticket.pairing?"pairing":"connect")+" code="+code);finish(job);
   });}
   public void onServiceResolved(NsdServiceInfo info){main.post(()->{
    if(session!=own||resolving!=job)return;
    if(running&&state.current(job.ticket)){
     if(state.resolved(job.ticket,info.getPort(),own(info)))AppLog.line("ADB_DISCOVERY","Resolved role="+(job.ticket.pairing?"pairing":"connect")+" port="+info.getPort());
     else rejectedCount++;
    }finish(job);
   });}
  };
  job.timeout=()->{if(session!=own||resolving!=job)return;timedOut++;cancelResolution(job);AppLog.line("ADB_DISCOVERY","Resolve timeout; advancing discovery queue");finish(job);};
  main.postDelayed(job.timeout,5000);
  try{manager.resolveService(job.info,job.listener);}catch(Exception e){failedCount++;finish(job);}
 }
 private void cancelResolution(Job job){if(Build.VERSION.SDK_INT>=34)try{manager.stopServiceResolution(job.listener);}catch(Exception ignored){}}
 void stop(){main.post(()->{
  running=false;session++;Job job=resolving;resolving=null;if(job!=null){main.removeCallbacks(job.timeout);cancelResolution(job);}
  pending.clear();state.clear();for(NsdManager.DiscoveryListener l:listeners)try{manager.stopServiceDiscovery(l);}catch(Exception ignored){}listeners.clear();publish();
 });}
}
