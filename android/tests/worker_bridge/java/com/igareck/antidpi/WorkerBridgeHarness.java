package com.igareck.antidpi;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import android.content.Context;
import rikka.shizuku.Shizuku;
import org.json.*;

/** Production Bridge + production adapter + real TCP. Android launcher/TAP are fixtures. */
public final class WorkerBridgeHarness {
 static int checks;
 static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 interface Attempt {void run()throws Exception;}
 static void reject(Attempt a,String message)throws Exception{boolean failed=false;try{a.run();}catch(Exception expected){failed=true;}check(failed,message);}
 static void set(String field,Object value)throws Exception{Field f=PacketBridge.class.getDeclaredField(field);f.setAccessible(true);f.set(null,value);}
 static Object get(String field)throws Exception{Field f=PacketBridge.class.getDeclaredField(field);f.setAccessible(true);return f.get(null);}
 static void awaitUnavailable()throws Exception{
  long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(9);
  while(PacketBridge.hasActiveWorker()){
   if(System.nanoTime()>until)throw new AssertionError("Dead channel stayed active");
   Thread.sleep(25);
  }
 }
 public static void main(String[] args)throws Exception{
  boolean success=false;try{
   IWorkerLauncher.Stub.realChannel=true;Context context=new Context();PacketBridge.init(context);
   JSONObject started=PacketBridge.start("N20",1080,true,true,"Q5","D1");String instance=started.getString("instance");
   check(started.optBoolean("native_alive")&&started.getString("profile").equals("T20")&&Shizuku.binds==1,"Start failed");
   Shizuku.target.drainFails=true;
   check(PacketBridge.refresh().getBoolean("native_alive")&&PacketBridge.hasActiveWorker()&&Shizuku.binds==1,"Failed log RPC invalidated the engine connection");
   Shizuku.target.drainFails=false;
   // Reproduce the field event: launcher Binder actually dies, Shizuku is absent.
   Shizuku.target.die();Shizuku.disconnect();Shizuku.available=false;
   JSONObject live=PacketBridge.refresh();check(live.getString("instance").equals(instance)&&live.getBoolean("native_alive"),"Broker death lost live engine status");
   check(new JSONObject(PacketBridge.snapshot()).getJSONObject("bridge").getBoolean("connected"),"Broker death closed independent transport");
   check(PacketBridge.permitted(),"Ready worker lost authorization when ADB closed");
   Shizuku.target.tapClean=false;reject(PacketBridge::stop,"False TAP cleanup was accepted");check(PacketBridge.hasPendingCleanup(),"Unconfirmed cleanup forgotten");
   Shizuku.target.tapClean=true;PacketBridge.stop();check(!PacketBridge.hasPendingCleanup()&&!Shizuku.target.nativeAlive,"Stop failed while Shizuku absent");PacketBridge.stop();
   check(Shizuku.binds==1&&Shizuku.removes==0,"Stop required Shizuku rebind");
   String descriptor=context.getSharedPreferences("packet_resource_ownership",0).getString("worker","");String capability=new JSONObject(descriptor).getString("capability");
   check(!PacketBridge.snapshot().contains(capability)&&AppLog.lines.stream().noneMatch(s->s.contains(capability)),"Capability leaked into export/logs");
   // A re-created app state can reattach privately, without the dead launcher.
   PacketWorkerTransport old=(PacketWorkerTransport)get("worker");old.detach();set("worker",null);set("preferences",null);set("workerDescriptor","");PacketBridge.init(context);
   check(PacketBridge.refresh().getString("state").equals("STOPPED"),"Private channel reattach required Shizuku");
   check(Shizuku.binds==1,"Reattach spawned another launcher");
   // Start cancellation remains instance scoped while a real RPC is in flight.
   Shizuku.available=true;Shizuku.target.startEntered=new CountDownLatch(1);Shizuku.target.startRelease=new CountDownLatch(1);
   CompletableFuture<Void> startup=CompletableFuture.runAsync(()->{try{PacketBridge.start("T40",1080,false,true,"Q2","D3");}catch(Exception expected){}});
   check(Shizuku.target.startEntered.await(3,TimeUnit.SECONDS),"Start did not enter RPC");
   Shizuku.available=false;PacketBridge.cancelPending();Thread.sleep(100);Shizuku.target.startRelease.countDown();startup.get(3,TimeUnit.SECONDS);PacketBridge.stop();
   check(!PacketBridge.hasPendingCleanup()&&!Shizuku.target.nativeAlive,"Stop lost in-flight request during Shizuku loss");
   // Paired/authorized app, but Android has switched Wi-Fi debugging off.
   // A hundred monitor/Stop iterations must never start another ADB bootstrap.
   Shizuku.available=true;PacketBridge.start("T20",1080,false);int launched=Shizuku.binds;
   IWorkerLauncher.Stub.server.close();EmbeddedAdb.debuggingOff=true;awaitUnavailable();
   for(int i=0;i<100;i++){try{PacketBridge.refresh();}catch(Exception expected){}try{PacketBridge.stop();}catch(Exception expected){}}
   check(Shizuku.binds==launched,"Unavailable debugging caused an activation storm");
   check(PacketBridge.hasPendingCleanup(),"Missing worker erased unconfirmed packet ownership");
   EmbeddedAdb.debuggingOff=false;PacketBridge.activate();PacketBridge.stop();
   check(Shizuku.binds==launched+1&&!PacketBridge.hasPendingCleanup(),"Explicit activation did not complete saved Stop");
   IWorkerLauncher.Stub.server.close();awaitUnavailable();launched=Shizuku.binds;Shizuku.failNext=true;
   reject(PacketBridge::refresh,"Dead saved endpoint reattached");
   check(Shizuku.binds==launched,"Passive refresh activated ADB");
   for(int i=0;i<100;i++)try{PacketBridge.refresh(true,()->true);}catch(Exception expected){}
   check(Shizuku.binds==launched+1,"Allowed but failed activation ignored background retry gate");
   check((Long)get("launchRetryAfter")>android.os.SystemClock.elapsedRealtime(),"Failed launch did not set backoff");
   PacketBridge.activate();check(Shizuku.binds==launched+2,"Explicit user activation did not bypass backoff");
   PacketBridge.shutdownWorker();check(!PacketBridge.hasActiveWorker()&&context.getSharedPreferences("packet_resource_ownership",0).getString("worker","").isEmpty(),"Explicit shutdown retained private active-worker record");
   int beforeRestore=Shizuku.binds;
   check(!PacketBridge.reattachOnly()&&Shizuku.binds==beforeRestore,"Reattach-only opened ADB");
   reject(()->PacketBridge.restoreAutomatically(()->false),"Cancelled automatic launch was accepted");
   check(Shizuku.binds==beforeRestore,"Cancelled automatic launch opened ADB");
   PacketBridge.restoreAutomatically(()->true);
   check(PacketBridge.hasActiveWorker()&&Shizuku.binds==beforeRestore+1,"Automatic restore did not retain the real RPC helper");
   check(!Shizuku.target.nativeAlive&&!PacketBridge.hasPendingCleanup(),"Helper recovery started packet strategies");
   // A recently acknowledged socket may be closed immediately before Start.
   PacketWorkerTransport cached=(PacketWorkerTransport)get("worker");
   Field heartbeat=PacketWorkerTransport.class.getDeclaredField("heartbeat");heartbeat.setAccessible(true);
   ScheduledExecutorService beats=(ScheduledExecutorService)heartbeat.get(cached);
   beats.shutdownNow();
   check(beats.awaitTermination(1,TimeUnit.SECONDS),"Fixture did not stop heartbeat");
   IWorkerLauncher.Stub.server.close();
   check(PacketBridge.hasActiveWorker(),"Fixture needs a recently acknowledged endpoint");
   int beforeStart=Shizuku.binds;
   java.util.concurrent.atomic.AtomicBoolean wanted=new java.util.concurrent.atomic.AtomicBoolean(true);
   reject(()->PacketBridge.start("T20",1080,false,false,"Q1","D0",()->wanted.getAndSet(false)),"Cancelled reconnect sent a start");
   check(Shizuku.binds==beforeStart&&!PacketBridge.hasPendingCleanup(),"Cancelled status retry touched ownership or launched ADB");
   Field refused=PacketWorkerTransport.class.getDeclaredField("connectionRefused");refused.setAccessible(true);refused.setBoolean(cached,false);
   Shizuku.target=new FakeTransport();
   JSONObject afterRefusal=PacketBridge.start("T20",1080,false);
   check(afterRefusal.getBoolean("native_alive")&&Shizuku.binds==beforeStart+1&&Shizuku.target.starts==1,
        "Refused local status did not recover the pending Start exactly once");
   PacketBridge.stop();
   // A previous install's live worker must not be shown as active after an update.
   old=(PacketWorkerTransport)get("worker");old.detach();
   WorkerChannel.Server replacedServer=IWorkerLauncher.Stub.server;
   try{
    set("worker",null);set("preferences",null);set("workerDescriptor","");
    System.setProperty("igareck.test.apk","updated.apk");
    PacketBridge.init(context);
    int beforeUpdate=Shizuku.binds;
    check(!PacketBridge.reattachOnly()&&!PacketBridge.hasActiveWorker()&&Shizuku.binds==beforeUpdate,
         "A previous APK's channel was adopted on update");
    Shizuku.target=new FakeTransport();
    PacketBridge.activate();
    check(PacketBridge.hasActiveWorker()&&Shizuku.binds==beforeUpdate+1
      &&"updated.apk".equals(context.getSharedPreferences("packet_resource_ownership",0).getString("worker_apk","")),
      "Fresh activation was not tied to the current APK");
   }finally{replacedServer.close();}
   // A replacement connection must still be able to stop the old, authenticated owner.
   JSONObject prior=PacketBridge.start("T20",1080,false);
   FakeTransport oldOwner=Shizuku.target;int oldStops=oldOwner.stops;
   WorkerChannel.Server oldServer=IWorkerLauncher.Stub.server;
   try{
    ((PacketWorkerTransport)get("worker")).detach();set("worker",null);set("workerDescriptor","");
    FakeTransport replacement=new FakeTransport();Shizuku.target=replacement;PacketBridge.activate();
    check(!new JSONObject(replacement.status()).getString("service_epoch").equals(prior.getString("service_epoch")),"Fixture did not replace epoch");
    PacketBridge.stop();
    check(!oldOwner.nativeAlive&&oldOwner.stops==oldStops+1&&replacement.recoveries==0&&!PacketBridge.hasPendingCleanup(),"New helper lost old owner's authenticated Stop route");
   }finally{oldServer.close();}
   PacketBridge.shutdownWorker();
   // Both Stop paths are now unavailable. A verifier receipt may arrive before
   // an app restart and must still resolve exactly the saved packet ownership.
   Shizuku.target=new FakeTransport();
   JSONObject localRun=PacketBridge.start("T20",1080,false);
   String localInstance=localRun.getString("instance"),epoch=localRun.getString("service_epoch");
   String localCapability=new JSONObject(context.getSharedPreferences("packet_resource_ownership",0).getString("worker","")).getString("capability");
   int appUid=android.os.Process.myUid();
   String proof=StopVerifier.proof(StopVerifier.key(localCapability,appUid,localInstance,epoch),localInstance,epoch);
   int beforeLocal=Shizuku.binds;
   EmbeddedAdb.debuggingOff=true;Shizuku.available=false;
   IWorkerLauncher.Stub.server.close();awaitUnavailable();
   PacketBridge.recordVerifiedStop(context,localInstance,epoch,WorkerChannel.token());
   String stale=UUID.randomUUID().toString();
   PacketBridge.recordVerifiedStop(context,stale,epoch,StopVerifier.proof(StopVerifier.key(localCapability,appUid,stale,epoch),stale,epoch));
   reject(PacketBridge::stop,"Forged or stale proof resolved Stop");
   check(PacketBridge.hasPendingCleanup(),"Unverified cleanup lost its ownership record");
   int stopCalls=PacketControl.calls;
   PacketBridge.recordVerifiedStop(context,localInstance,epoch,proof);
   ((PacketWorkerTransport)get("worker")).detach();set("worker",null);set("preferences",null);set("workerDescriptor","");
   set("cleanupPending",false);set("pendingInstance","");set("pendingEpoch","");set("pendingWorker","");
   PacketBridge.init(context);PacketBridge.stop();
   JSONObject verified=new JSONObject(PacketBridge.snapshot());
   check("independent_local_verification".equals(verified.optString("stop_confirmation"))&&!PacketBridge.hasPendingCleanup(),"Independent proof was not persisted or did not resolve Stop");
   check(Shizuku.binds==beforeLocal&&PacketControl.calls==stopCalls,"Independent receipt still required one of the unavailable paths");
   check(!PacketBridge.snapshot().contains(proof)&&AppLog.lines.stream().noneMatch(s->s.contains(proof)),"Proof leaked into export/logs");
   System.out.println(new JSONObject().put("passed",true).put("checks",checks).put("production_bridge",true).put("production_adapter",true).put("real_loopback_sockets",true).put("android_bootstrap_tested",false));
   success=true;
  }catch(Throwable e){e.printStackTrace();}finally{if(IWorkerLauncher.Stub.server!=null)IWorkerLauncher.Stub.server.close();PacketWorkerTransport w=(PacketWorkerTransport)get("worker");if(w!=null)w.detach();System.exit(success?0:1);}
 }
}
