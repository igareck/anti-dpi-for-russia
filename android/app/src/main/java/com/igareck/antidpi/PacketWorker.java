/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
/* Context bootstrap adapted from Shizuku-API UserService (MIT),
 * Copyright (c) 2021 RikkaW; see licenses/Shizuku-API-MIT.txt. */
package com.igareck.antidpi;

import android.content.Context;
import android.os.*;
import android.os.Process;
import java.lang.reflect.Method;
import org.json.*;

/** Shell app_process independent of Shizuku's user-service record and process group. */
public final class PacketWorker {
 private static Context context(int appUid)throws Exception{
  Looper.prepareMainLooper();
  Class<?> threadClass=Class.forName("android.app.ActivityThread");
  Object thread=threadClass.getMethod("systemMain").invoke(null);
  Context system=(Context)threadClass.getMethod("getSystemContext").invoke(thread);
  Method asUser=Context.class.getDeclaredMethod("createPackageContextAsUser",String.class,int.class,UserHandle.class);
  asUser.setAccessible(true);
  // app_process already loaded the worker. A package context supplies its
  // services without running Application initialization in the shell process.
  return (Context)asUser.invoke(system,AppIdentity.PACKAGE_NAME,0,UserHandle.getUserHandleForUid(appUid));
 }
 public static void main(String[] args){
  String phase="uid";
  try{
   if(Process.myUid()!=2000)throw new SecurityException("Worker requires shell UID 2000");
   phase="session";WorkerSession.detach();
   if(args.length==1&&StopVerifier.MODE.equals(args[0])){
    phase="stop_verification";
    JSONObject request=StopVerifier.awaitRelease(System.in,System.out);
    StopVerifier.verify(context(request.getInt("app_uid")),request);
    System.exit(0);return;
   }
   phase="input";
   JSONObject input=new JSONObject(WorkerChannel.line(System.in,WorkerChannel.MAX_REQUEST));String capability=input.getString("capability");WorkerChannel.validateToken(capability);
   if(input.getInt("protocol")!=WorkerChannel.PROTOCOL)throw new SecurityException("Bootstrap protocol mismatch");
   String bootstrapNonce=input.getString("bootstrap_nonce");java.util.UUID.fromString(bootstrapNonce);
   int appUid=input.getInt("app_uid");phase="context";Context context=context(appUid);if(AppIdentity.resolve(context).uid!=appUid)throw new SecurityException("Worker app UID mismatch");
   phase="engine";
   final NativePacketService service=new NativePacketService(context,true,capability);final IBinder owner=new Binder();
   ActivationPermission.grantToOwnApp(context,appUid);
   String epoch=new JSONObject(service.status()).getString("service_epoch");
   phase="channel";WorkerChannel.Server server=new WorkerChannel.Server(capability,epoch,new WorkerChannel.Endpoint(){
    public String call(String op,JSONObject a)throws Exception{
     switch(op){
      case "uid":return Integer.toString(service.getUid());
      case "status":return service.status();
      case "logs":return service.drainLogs();
      case "metrics":return service.snapshotMetrics();
      case "stop":return service.stopProxy(a.getString("instance"));
      case "recover":return service.recoverProxy(a.getString("instance"),a.getString("epoch"));
      case "start":return service.startProxyUdpDiscord(a.getString("profile"),a.getInt("port"),a.getBoolean("verbose"),owner,a.getString("instance"),a.getBoolean("udp"),a.getString("quic"),a.getString("discord"));
      case "scan":JSONArray j=a.getJSONArray("numbers");if(j.length()<1||j.length()>5)throw new IllegalArgumentException("Parallel count must be 1–5");int[] n=new int[j.length()];for(int i=0;i<n.length;i++)n[i]=j.getInt(i);return service.startYoutube(n,owner,a.getString("instance"));
      case "shutdown":new Thread(()->{try{Thread.sleep(300);}catch(InterruptedException ignored){}service.destroy();},"packet-user-shutdown").start();return "{}";
      default:throw new SecurityException("Unknown worker operation");
     }
    }
    public void ownerLost(){service.releaseOwner();}
    public void abandoned(){service.destroy();}
   },WorkerChannel.LEASE_MS,true);
   // Retention never crosses uninstall/update or a change of package ownership.
   final android.content.pm.ApplicationInfo installed=AppIdentity.resolve(context);
   Thread packageWatch=new Thread(()->{for(;;){try{Thread.sleep(30000);android.content.pm.ApplicationInfo now=context.getPackageManager().getApplicationInfo(AppIdentity.PACKAGE_NAME,0);if(now.uid!=appUid||!now.enabled||(now.flags&android.content.pm.ApplicationInfo.FLAG_STOPPED)!=0||!installed.sourceDir.equals(now.sourceDir)){service.destroy();return;}}catch(InterruptedException e){return;}catch(Exception removed){service.destroy();return;}}},"packet-package-watch");packageWatch.setDaemon(true);packageWatch.start();
   String descriptor=new JSONObject().put("protocol",WorkerChannel.PROTOCOL).put("port",server.port()).put("epoch",epoch).put("pid",Process.myPid()).put("application_id",AppIdentity.PACKAGE_NAME).toString();
   // Explicit receiver requires Android's DUMP sender permission. A fake local
   // ADB listener cannot authenticate a worker by merely echoing a shell reply.
   phase="receipt";java.lang.Process notify=new ProcessBuilder("/system/bin/am","broadcast","--user",Integer.toString(appUid/100000),"--receiver-foreground","-n",AppIdentity.PACKAGE_NAME+"/"+WorkerBootstrapReceiver.class.getName(),"-a",AdbBootstrap.ACTION,"--es","nonce",bootstrapNonce,"--es","descriptor",descriptor,"--es","auth",AdbBootstrap.proof(capability,bootstrapNonce,descriptor)).redirectErrorStream(true).start();
   if(!notify.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)||notify.exitValue()!=0){notify.destroy();throw new java.io.IOException("Bootstrap broadcast failed");}
   phase="ready";notify.getInputStream().close();System.out.println("IGARECK_WORKER_READY");System.out.flush();
   WorkerSession.releaseBootstrapStdio();
   Looper.loop();
  }catch(Throwable e){System.out.println(WorkerFailure.marker(phase,e));System.out.flush();System.exit(1);}
 }
 private PacketWorker(){}
}
