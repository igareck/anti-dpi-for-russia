/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Verifies one released run in a detached process and reports it through Android. */
final class StopVerifier {
 static final String ACTION=AppIdentity.PACKAGE_NAME+".STOP_VERIFIED";
 static final String MODE="verify-stop";
 private static final String READY="IGARECK_STOP_VERIFIER_READY";
 private static final File DEV_NULL=new File("/dev/null");

 static String key(String capability,int appUid,String instance,String epoch)throws Exception {
  return WorkerChannel.mac(capability,"stop-verification-key\n1\n"+appUid+"\n"+instance+"\n"+epoch);
 }
 static String proof(String key,String instance,String epoch)throws Exception {
  return WorkerChannel.mac(key,"stop-verified\n1\n"+instance+"\n"+epoch);
 }
 static boolean authentic(String descriptor,int appUid,String instance,String epoch,String auth)throws Exception {
  if(descriptor.isEmpty()||auth==null||auth.length()!=64)return false;
  JSONObject owner=new JSONObject(descriptor);
  if(!epoch.equals(owner.getString("epoch")))return false;
  return WorkerChannel.equal(proof(key(owner.getString("capability"),appUid,instance,epoch),instance,epoch),auth);
 }

 static OutputStream launch(ApplicationInfo app,String instance,String epoch,String capability,Consumer<String> log)throws Exception {
  JSONObject request=new JSONObject().put("app_uid",app.uid).put("instance",instance).put("epoch",epoch)
   .put("key",key(capability,app.uid,instance,epoch));
  ProcessBuilder builder=new ProcessBuilder("/system/bin/app_process","/system/bin",
   "--nice-name="+AppIdentity.PACKAGE_NAME+":stop_verifier",AppIdentity.PACKAGE_NAME+".PacketWorker",MODE);
  builder.environment().put("CLASSPATH",app.sourceDir);
  Process verifier=builder.redirectError(DEV_NULL).start();
  OutputStream lease=verifier.getOutputStream();
  try{WorkerChannel.write(lease,request.toString(),1024);}
  catch(IOException e){verifier.destroy();throw e;}
  Thread readiness=new Thread(()->{
   try(InputStream in=verifier.getInputStream()){
    if(!READY.equals(WorkerChannel.line(in,128)))throw new IOException("Readiness marker missing");
    log.accept("Ready; instance="+instance);
   }catch(IOException unavailable){log.accept("Unavailable; instance="+instance);}
  },"packet-stop-verifier-ready");
  readiness.setDaemon(true);readiness.start();
  return lease;
 }

 static JSONObject awaitRelease(InputStream in,PrintStream out)throws Exception {
  JSONObject request=new JSONObject(WorkerChannel.line(in,1024));
  PacketRecovery.canonicalId(request.getString("instance"));PacketRecovery.canonicalId(request.getString("epoch"));
  WorkerChannel.validateToken(request.getString("key"));
  out.println(READY);out.flush();
  // The only writer is the packet worker. EOF follows clean release or process death.
  if(in.read()!=-1)throw new IOException("Unexpected verifier lease data");
  return request;
 }

 static void verify(Context context,JSONObject request)throws Exception {
  int appUid=request.getInt("app_uid");
  if(AppIdentity.resolve(context).uid!=appUid)throw new SecurityException("Verifier app UID mismatch");
  String instance=request.getString("instance"),epoch=request.getString("epoch");
  long deadline=SystemClock.elapsedRealtime()+30000;
  for(;;){
   try(PacketRecovery recovery=new PacketRecovery(context,appUid,epoch)){
    recovery.verifyReleasedRun(instance,epoch);
    break;
   }catch(InterruptedException e){throw e;}
   catch(Exception pending){
    if(SystemClock.elapsedRealtime()>=deadline)throw pending;
    Thread.sleep(500);
   }
  }
  Process notify=new ProcessBuilder("/system/bin/am","broadcast","--user",Integer.toString(appUid/100000),
   "--receiver-foreground","-n",AppIdentity.PACKAGE_NAME+"/"+AppIdentity.PACKAGE_NAME+".WorkerBootstrapReceiver",
   "-a",ACTION,"--es","instance",instance,"--es","epoch",epoch,"--es","auth",proof(request.getString("key"),instance,epoch))
   .redirectOutput(DEV_NULL).redirectError(DEV_NULL).start();
  try{
   if(!notify.waitFor(10,TimeUnit.SECONDS)||notify.exitValue()!=0)throw new IOException("Stop verifier receipt delivery failed");
  }finally{if(notify.isAlive())notify.destroyForcibly();}
 }
 private StopVerifier(){}
}
