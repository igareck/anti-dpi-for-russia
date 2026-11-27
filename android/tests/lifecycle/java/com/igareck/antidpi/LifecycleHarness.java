/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import android.content.*;
import android.os.*;
import rikka.shizuku.Shizuku;

/** Runs production control classes. Android/Binder/TAP are explicit host fixtures. */
public final class LifecycleHarness {
 interface Checked {void run()throws Exception;}
 static final JSONArray results=new JSONArray();
 static final ExecutorService worker;
 static {try{Field f=ProxyService.class.getDeclaredField("WORKER");f.setAccessible(true);worker=(ExecutorService)f.get(null);}catch(Exception e){throw new ExceptionInInitializerError(e);}}
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 static void expectFailure(Checked c)throws Exception{try{c.run();}catch(Exception expected){return;}throw new AssertionError("Expected rejected cleanup");}
 static void set(Object o,String field,Object v)throws Exception{Field f=(o instanceof Class?(Class<?>)o:o.getClass()).getDeclaredField(field);f.setAccessible(true);f.set(o instanceof Class?null:o,v);}
 static void await(Callable<Boolean> c)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!c.call()){if(System.nanoTime()>end)throw new AssertionError("Timed out: "+ProxyService.snapshot());Thread.sleep(5);}}
 static void barrier()throws Exception{worker.submit(()->{}).get(5,TimeUnit.SECONDS);Handler.MAIN.submit(()->{}).get(5,TimeUnit.SECONDS);}
 static void reset()throws Exception{
  barrier();set(PacketBridge.class,"worker",null);set(PacketBridge.class,"workerDescriptor","");set(PacketBridge.class,"cached","{\"state\":\"NOT_CONNECTED\"}");
  set(PacketBridge.class,"preferences",null);set(PacketBridge.class,"cleanupPending",false);set(PacketBridge.class,"pendingInstance","");set(PacketBridge.class,"pendingEpoch","");set(PacketBridge.class,"pendingWorker","");set(PacketBridge.class,"cancelRequestedFor","");set(PacketBridge.class,"launchRetryAfter",0L);set(PacketBridge.class,"stopReceipt","");
  Context.failCommits=false;PacketControl.reply=null;PacketControl.calls=0;Shizuku.target=new FakeTransport();Shizuku.available=true;Shizuku.failNext=false;Shizuku.suppressNext=false;Shizuku.connections.clear();Shizuku.binds=Shizuku.removes=Shizuku.detaches=0;
  EmbeddedAdb.wifiDisconnected=false;
  ProxyService.activeUdp=false;ProxyService.activeQuicId="Q1";ProxyService.activeDiscordId="D0";ProxyService.running=false;ProxyService.activeInstance="none";ProxyService.failure="";ProxyService.activeBackend="nfqws2";
 }
 static void test(String name,Checked body)throws Exception{reset();long t=System.nanoTime();body.run();results.put(new JSONObject().put("name",name).put("passed",true).put("elapsed_ms",(System.nanoTime()-t)/1000000));}
 static Intent start(String id){return new Intent().setAction(ProxyService.START).putExtra("profile",id).putExtra("port",1080).putExtra("ttl",2);}
 static Intent stop(){return new Intent().setAction(ProxyService.STOP);}
 static void startAndWait(ProxyService s,String id,int n)throws Exception{s.onStartCommand(start(id),0,n);barrier();check(ProxyService.running&&ProxyService.activeId.equals(id),"Expected running "+id+": "+ProxyService.snapshot());}
 static void stopAndWait(ProxyService s,int n)throws Exception{s.onStartCommand(stop(),0,n);barrier();check(!ProxyService.running&&ProxyService.failure.isEmpty(),"Stop left failure");}
 static void oldBaseline()throws Exception{
  PacketBridge.start("T1",1080,false);PacketBridge.stop();boolean threw=false;
  try{PacketBridge.stop();}catch(Exception e){threw=true;}
  check(threw,"Old bridge must reproduce repeated Stop failure after confirmed cleanup");
  System.out.println(new JSONObject().put("baseline_reproduced",true).put("confirmed_cleanup_then_repeated_stop_fails",true).put("destructive_unbind_count",Shizuku.removes));
 }
 static void run()throws Exception{
  test("saved pairing without Wi-Fi keeps activation required after foreground teardown",()->{
   Context context=new Context();PacketBridge.init(context);
   ProxyService service=new ProxyService();service.onCreate();EmbeddedAdb.wifiDisconnected=true;
   service.onStartCommand(start("T30"),0,1);barrier();
   check(ProxyService.state.equals(ProxyService.ACTIVATION_REQUIRED),"Missing Wi-Fi did not require activation");
   String hint=ProxyService.failure;
   check(hint.contains("Сопряжение сохранено")&&hint.contains("Wi-Fi")&&hint.contains("Запуск через USB"),"Activation guidance missing");
   UiLanguage.toggle(context);
   try{
    check("Network module activation required".equals(UiLanguage.text(context,ProxyService.state)),"English status missing");
    check("Pairing saved. Connect to Wi-Fi or use Start over USB from a computer.".equals(UiLanguage.text(context,hint)),"English guidance missing");
   }finally{UiLanguage.toggle(context);}
   check(!ProxyService.hasRunRequest()&&!PacketBridge.hasPendingCleanup()&&Shizuku.target.starts==0,"Precondition started or retained native resources");
   service.onDestroy();barrier();
   check(ProxyService.state.equals(ProxyService.ACTIVATION_REQUIRED)&&ProxyService.failure.equals(hint),"Teardown erased activation guidance");
   EmbeddedAdb.wifiDisconnected=false;
   ProxyService retry=new ProxyService();retry.onCreate();startAndWait(retry,"T30",2);
   check(ProxyService.failure.isEmpty(),"Successful Start kept the old hint");
   stopAndWait(retry,3);retry.onDestroy();barrier();
  });
  test("100 N1/N20/N484 Apply and repeated Stop cycles reuse helper",()->{
   for(int i=0;i<100;i++){String p=i%4==3?"T484":i%3==2?"T20":"T1";JSONObject s=PacketBridge.start(p,1080,false);check(s.getString("state").equals("RUNNING"),"not running");PacketBridge.stop();PacketBridge.stop();}
   check(Shizuku.target.starts==100&&Shizuku.binds==1&&Shizuku.removes==0,"Unexpected helper lifetime");
  });
  test("confirmed Stop survives binder disconnect and unavailable Shizuku",()->{
   PacketBridge.start("T1",1080,false);PacketBridge.stop();Shizuku.disconnect();Shizuku.available=false;PacketBridge.stop();
   check(!PacketBridge.hasPendingCleanup(),"Clean resources became pending");check(new JSONObject(PacketBridge.snapshot()).getJSONObject("bridge").getBoolean("stop_confirmed"),"Receipt lost");
  });
  test("dead worker reactivation keeps the new private control session",()->{
   PacketBridge.start("T1",1080,false);FakeTransport old=Shizuku.target;old.die();Shizuku.target=new FakeTransport();Shizuku.target.recoverable=true;PacketBridge.refresh(true,()->true);
   check(new JSONObject(PacketBridge.snapshot()).getJSONObject("bridge").getBoolean("connected"),"New control session missing");PacketBridge.stop();check(Shizuku.binds==2&&Shizuku.removes==0,"Worker replacement destroyed another owner");
  });
  test("bind failure before RPC creates no cleanup debt and next attempt succeeds",()->{
   Shizuku.failNext=true;expectFailure(()->PacketBridge.start("T1",1080,false));check(!PacketBridge.hasPendingCleanup(),"Bind-only failure owes cleanup");
   PacketBridge.stop();PacketBridge.start("T20",1080,false);PacketBridge.stop();
  });
  test("bind timeout recovers without reinstall",()->{
   Shizuku.suppressNext=true;expectFailure(()->PacketBridge.start("T1",1080,false));check(!PacketBridge.hasPendingCleanup(),"Timeout before RPC owes cleanup");
   PacketBridge.start("T20",1080,false);PacketBridge.stop();
  });
  test("TAP cleanup failure remains pending and retry can confirm",()->{
   PacketBridge.start("T1",1080,false);Shizuku.target.tapClean=false;expectFailure(PacketBridge::stop);check(PacketBridge.hasPendingCleanup(),"False TAP cleanup was accepted");
   Shizuku.target.tapClean=true;PacketBridge.stop();check(!PacketBridge.hasPendingCleanup(),"Retry did not clear cleanup debt");
  });
  test("workspace cleanup failure is rejected",()->{
   PacketBridge.start("T1",1080,false);Shizuku.target.workspace=false;expectFailure(PacketBridge::stop);check(PacketBridge.hasPendingCleanup(),"False workspace receipt was accepted");Shizuku.target.workspace=true;PacketBridge.stop();
  });
  test("live native process prevents successful Stop receipt",()->{
   PacketBridge.start("T1",1080,false);Shizuku.target.forcedAlive=true;expectFailure(PacketBridge::stop);check(PacketBridge.hasPendingCleanup(),"Live native child was accepted");Shizuku.target.forcedAlive=false;PacketBridge.stop();
  });
  test("different instance or helper epoch cannot prove old cleanup",()->{
   PacketBridge.start("T1",1080,false);Shizuku.target.forcedInstance=UUID.randomUUID().toString();expectFailure(PacketBridge::stop);
   Shizuku.target.forcedInstance=null;Shizuku.target.forcedEpoch=UUID.randomUUID().toString();expectFailure(PacketBridge::stop);
   check(PacketBridge.hasPendingCleanup(),"Foreign receipt was accepted");Shizuku.target.forcedEpoch=null;PacketBridge.stop();
  });
  test("Shizuku loss and replacement helper recover old resource ownership",()->{
   JSONObject before=PacketBridge.start("T20",1080,false);String old=before.getString("instance");
   Shizuku.target.die();Shizuku.disconnect();Shizuku.available=false;expectFailure(PacketBridge::stop);check(PacketBridge.hasPendingCleanup(),"Lost cleanup obligation");
   FakeTransport replacement=new FakeTransport();replacement.recoverable=true;Shizuku.target=replacement;Shizuku.available=true;
   PacketBridge.stop();PacketBridge.stop();check(!PacketBridge.hasPendingCleanup()&&replacement.recoveries==1,"Verified restart recovery failed");
   JSONObject proof=new JSONObject(PacketBridge.snapshot()).getJSONObject("recovery");check(proof.getString("previous_instance").equals(old),"Wrong recovered instance");
   PacketBridge.start("T20",1080,false);PacketBridge.stop();
  });
  test("recovery failure remains pending and later verified retry succeeds",()->{
   PacketBridge.start("T20",1080,false);Shizuku.target.die();Shizuku.disconnect();Shizuku.target=new FakeTransport();
   expectFailure(PacketBridge::stop);check(PacketBridge.hasPendingCleanup(),"Unverified new helper cleared old debt");
   Shizuku.target.recoverable=true;PacketBridge.stop();check(!PacketBridge.hasPendingCleanup(),"Recovery retry failed");
  });
  test("forged or incomplete recovery receipt is rejected",()->{
   JSONObject before=PacketBridge.start("T20",1080,false);Shizuku.target.die();Shizuku.disconnect();FakeTransport next=new FakeTransport();next.recoverable=true;Shizuku.target=next;
   JSONObject receipt=new JSONObject().put("verified",true).put("native_absent",true).put("interfaces_absent",true).put("workspace_cleanup_ok",true)
    .put("previous_instance",before.getString("instance")).put("previous_service_epoch",before.getString("service_epoch"));
   next.recoveryOverride=receipt.put("previous_instance",UUID.randomUUID().toString()).toString();expectFailure(PacketBridge::stop);
   next.recoveryOverride=receipt.put("previous_instance",before.getString("instance")).put("interfaces_absent",false).toString();expectFailure(PacketBridge::stop);
   check(PacketBridge.hasPendingCleanup(),"Forged recovery cleared obligation");next.recoveryOverride=null;PacketBridge.stop();
  });
  test("pending ownership survives app process state reload",()->{
   Context context=new Context();PacketBridge.init(context);PacketBridge.start("T20",1080,false);
   Shizuku.target.die();Shizuku.disconnect();Shizuku.target=new FakeTransport();Shizuku.target.recoverable=true;
   set(PacketBridge.class,"preferences",null);set(PacketBridge.class,"cleanupPending",false);set(PacketBridge.class,"pendingInstance","");set(PacketBridge.class,"pendingEpoch","");
   PacketBridge.init(context);check(PacketBridge.hasPendingCleanup(),"Restart forgot durable obligation");PacketBridge.stop();
   check(context.getSharedPreferences("packet_resource_ownership",0).getString("pending","missing").isEmpty(),"Confirmed Stop was not persisted");
  });
  test("production ProxyService resumes Apply after Shizuku replacement",()->{
   ProxyService s=new ProxyService();s.onCreate();startAndWait(s,"T20",1);Shizuku.target.die();Shizuku.disconnect();Shizuku.available=false;
   s.onStartCommand(stop(),0,2);barrier();check(!ProxyService.running&&PacketBridge.hasPendingCleanup(),"Expected pending cleanup while offline");
   Shizuku.target=new FakeTransport();Shizuku.target.recoverable=true;Shizuku.available=true;
   startAndWait(s,"T20",3);check(Shizuku.target.recoveries==1&&ProxyService.failure.isEmpty(),"Apply did not recover old helper");stopAndWait(s,4);s.onDestroy();barrier();
  });
  test("failed persistence before start creates neither native resources nor cleanup debt",()->{
   Context context=new Context();PacketBridge.init(context);Context.failCommits=true;
   expectFailure(()->PacketBridge.start("T20",1080,false));
   check(!PacketBridge.hasPendingCleanup()&&Shizuku.target.starts==0,"Failed disk write initiated engine or debt");
   Context.failCommits=false;PacketBridge.start("T20",1080,false);PacketBridge.stop();
  });
  test("failed persistence of Stop retains obligation until successful retry",()->{
   PacketBridge.init(new Context());PacketBridge.start("T20",1080,false);Context.failCommits=true;
   expectFailure(PacketBridge::stop);check(PacketBridge.hasPendingCleanup(),"Failed clear forgot persisted debt");
   expectFailure(()->PacketBridge.start("T1",1080,false));Context.failCommits=false;PacketBridge.stop();
   check(!PacketBridge.hasPendingCleanup(),"Persistence retry failed");
  });
  test("production helper retains failed TAP cleanup until a second matching Stop",()->{
   NativePacketService n=new NativePacketService(new Context());String own=UUID.randomUUID().toString();TapSession tap=new TapSession(new Context());tap.failCloseOnce=true;
   set(n,"instance",own);set(n,"state","RUNNING");set(n,"tap",tap);
   JSONObject failed=new JSONObject(n.stopProxy(own));check(failed.getString("state").equals("ERROR")&&!failed.getBoolean("workspace_cleanup_ok")&&tap.closes==1,"Failed TAP cleanup was acknowledged");
   JSONObject stopped=new JSONObject(n.stopProxy(own));check(stopped.getString("state").equals("STOPPED")&&stopped.getBoolean("workspace_cleanup_ok")&&stopped.getJSONObject("tap").getBoolean("cleanup_ok")&&tap.closes==2,"Retry lost retained TAP");
   for(Thread t:Thread.getAllStackTraces().keySet())if(t.getName().equals("packet-idle-exit"))t.interrupt();
  });
  test("existing owned Binder permits refresh and Stop while Shizuku is offline",()->{
   PacketBridge.start("T20",1080,false);FakeTransport owned=Shizuku.target;
   Shizuku.available=false;Shizuku.disconnect();
   check(PacketBridge.refresh().getBoolean("native_alive"),"Broker outage incorrectly hid the live helper");
   PacketBridge.stop();check(!PacketBridge.hasPendingCleanup()&&owned.stops>0&&Shizuku.binds==1,"Offline Stop did not use owned Binder");
  });
  test("local control receipt confirms Stop after loss of Binder and broker",()->{
   JSONObject before=PacketBridge.start("T20",1080,false);String id=before.getString("instance");
   Shizuku.target.die();Shizuku.available=false;
   PacketControl.reply=new JSONObject().put("state","STOPPED").put("instance",id).put("service_epoch",before.getString("service_epoch"))
    .put("native_alive",false).put("workspace_cleanup_ok",true).put("tap",new JSONObject().put("cleanup_ok",true)).toString();
   PacketBridge.stop();check(!PacketBridge.hasPendingCleanup()&&PacketControl.calls==1&&id.equals(PacketControl.instance),"Local Stop channel was not used");
  });
  test("unmatched local control receipt cannot clear ownership",()->{
   PacketBridge.start("T20",1080,false);Shizuku.target.die();Shizuku.available=false;
   PacketControl.reply=new JSONObject().put("state","STOPPED").put("instance",UUID.randomUUID().toString()).put("service_epoch",UUID.randomUUID().toString())
    .put("native_alive",false).put("workspace_cleanup_ok",true).put("tap",new JSONObject().put("cleanup_ok",true)).toString();
   expectFailure(PacketBridge::stop);check(PacketBridge.hasPendingCleanup(),"Foreign local acknowledgement was accepted");
  });
  test("monitor keeps running during broker outage and clears transient warnings after recovery",()->{
   ProxyService s=new ProxyService();s.onCreate();startAndWait(s,"T20",1);Shizuku.available=false;Shizuku.disconnect();
   Thread.sleep(850);barrier();check(ProxyService.running&&ProxyService.state.equals("Работает"),"Broker outage stopped or hid working traffic");
   ProxyService.failure="old transient warning";Shizuku.available=true;Thread.sleep(850);barrier();check(ProxyService.failure.isEmpty(),"Monitor did not clear obsolete warning");
   stopAndWait(s,2);s.onDestroy();barrier();
  });
  test("an actual transient monitor RPC error recovers without restarting native engine",()->{
   ProxyService s=new ProxyService();s.onCreate();startAndWait(s,"T20",1);FakeTransport t=Shizuku.target;
   t.monitorEntered=new CountDownLatch(1);t.monitorRelease=new CountDownLatch(1);t.monitorThrows=true;
   check(t.monitorEntered.await(2,TimeUnit.SECONDS),"Monitor did not enter injected error");t.monitorRelease.countDown();
   Thread.sleep(1600);barrier();check(ProxyService.running&&ProxyService.state.equals("Работает")&&ProxyService.failure.isEmpty(),"Transient error changed the visible connection state");
   check(t.starts==1&&t.stops==0,"Transient error restarted or stopped the engine");stopAndWait(s,2);s.onDestroy();barrier();
  });
  test("Stop waits honestly when all channels are lost and auto-confirms after Shizuku returns",()->{
   ProxyService s=new ProxyService();s.onCreate();startAndWait(s,"T20",1);Shizuku.target.die();Shizuku.available=false;
   s.onStartCommand(stop(),0,2);barrier();check(ProxyService.state.equals("Остановка ожидает связи")&&PacketBridge.hasPendingCleanup(),"Missing channel falsely confirmed cleanup");
   Shizuku.target=new FakeTransport();Shizuku.target.recoverable=true;Shizuku.available=true;
   await(()->!PacketBridge.hasPendingCleanup());barrier();check(ProxyService.state.equals("Остановлен")&&ProxyService.failure.isEmpty(),"Stop did not complete automatically");s.onDestroy();barrier();
  });
  test("pending Stop completes automatically after independent proof while both channels remain down",()->{
   Context context=new Context();PacketBridge.init(context);
   ProxyService service=new ProxyService();service.onCreate();startAndWait(service,"T20",1);
   android.content.SharedPreferences prefs=context.getSharedPreferences("packet_resource_ownership",0);
   JSONObject pending=new JSONObject(prefs.getString("pending",""));
   JSONObject descriptor=new JSONObject(pending.getString("worker"));
   String id=pending.getString("instance"),epoch=pending.getString("epoch");
   String proof=StopVerifier.proof(StopVerifier.key(descriptor.getString("capability"),android.os.Process.myUid(),id,epoch),id,epoch);
   int activations=Shizuku.binds;Shizuku.target.die();Shizuku.available=false;
   service.onStartCommand(stop(),0,2);barrier();check(PacketBridge.hasPendingCleanup(),"Stop reported before verification");
   PacketBridge.recordVerifiedStop(context,id,epoch,proof);
   await(()->!PacketBridge.hasPendingCleanup());barrier();
   check(ProxyService.state.equals("Остановлен")&&Shizuku.binds==activations,"Independent verification failed to stop waiting locally");
   service.onDestroy();barrier();
  });
  test("T20/N23/N40 automatically restart after actual helper replacement with exact applied settings",()->{
   for(String id:new String[]{"T20","T23","T40"}){
    ProxyService service=new ProxyService();service.onCreate();
    service.onStartCommand(start(id).putExtra("port",12080).putExtra("verbose",true),0,1);barrier();
    String old=ProxyService.activeInstance;Shizuku.available=false;Shizuku.target.die();
    await(()->ProxyService.state.equals("Нет связи с движком"));
    FakeTransport replacement=new FakeTransport();replacement.recoverable=true;Shizuku.target=replacement;Shizuku.available=true;
    await(()->replacement.starts==1&&ProxyService.running&&!old.equals(ProxyService.activeInstance));
    check(replacement.recoveries==1&&replacement.profile.equals(id)&&replacement.lastPort==12080&&replacement.lastVerbose,"Applied settings or verified cleanup lost");
    check(ProxyService.failure.isEmpty()&&ProxyService.hasRunRequest(),"Restored state lost run intent");
    stopAndWait(service,2);service.onDestroy();barrier();
   }
  });
  test("automatic restore waits for verified resource receipt before any new start",()->{
   ProxyService service=new ProxyService();service.onCreate();startAndWait(service,"T20",1);
   Shizuku.available=false;Shizuku.target.die();await(()->ProxyService.state.equals("Нет связи с движком"));
   FakeTransport replacement=new FakeTransport();replacement.recoverable=true;replacement.recoveryOverride="{}";Shizuku.target=replacement;Shizuku.available=true;
   await(()->ProxyService.state.equals("Восстановление ожидает очистки"));
   check(replacement.starts==0&&PacketBridge.hasPendingCleanup(),"Unverified cleanup started an engine");
   replacement.recoveryOverride=null;await(()->ProxyService.running&&replacement.starts==1);stopAndWait(service,2);service.onDestroy();barrier();
  });
  test("explicit Stop during broker outage cancels automatic restart after return",()->{
   ProxyService service=new ProxyService();service.onCreate();startAndWait(service,"T23",1);
   Shizuku.available=false;Shizuku.target.die();await(()->ProxyService.state.equals("Нет связи с движком"));
   service.onStartCommand(stop(),0,2);barrier();check(!ProxyService.hasRunRequest(),"Stop did not cancel run intent");
   FakeTransport replacement=new FakeTransport();replacement.recoverable=true;Shizuku.target=replacement;Shizuku.available=true;
   await(()->!PacketBridge.hasPendingCleanup());barrier();Thread.sleep(800);check(replacement.starts==0&&ProxyService.state.equals("Остановлен"),"Cancelled strategy restarted");service.onDestroy();barrier();
  });
  test("Stop during blocked restore cleanup cannot start a replacement",()->{
   ProxyService service=new ProxyService();service.onCreate();startAndWait(service,"T20",1);
   Shizuku.available=false;Shizuku.target.die();await(()->ProxyService.state.equals("Нет связи с движком"));
   FakeTransport replacement=new FakeTransport();replacement.recoverable=true;replacement.recoverEntered=new CountDownLatch(1);replacement.recoverRelease=new CountDownLatch(1);Shizuku.target=replacement;Shizuku.available=true;
   check(replacement.recoverEntered.await(6,TimeUnit.SECONDS),"Restore did not reach cleanup");service.onStartCommand(stop(),0,2);replacement.recoverRelease.countDown();barrier();Thread.sleep(100);
   check(replacement.starts==0&&!ProxyService.hasRunRequest()&&!PacketBridge.hasPendingCleanup(),"Late cleanup resurrected stopped strategy");service.onDestroy();barrier();
  });
  test("new Apply supersedes a blocked automatic restore without starting the old recipe",()->{
   ProxyService service=new ProxyService();service.onCreate();startAndWait(service,"T20",1);
   Shizuku.available=false;Shizuku.target.die();await(()->ProxyService.state.equals("Нет связи с движком"));
   FakeTransport replacement=new FakeTransport();replacement.recoverable=true;replacement.recoverEntered=new CountDownLatch(1);replacement.recoverRelease=new CountDownLatch(1);Shizuku.target=replacement;Shizuku.available=true;
   check(replacement.recoverEntered.await(6,TimeUnit.SECONDS),"Restore did not enter cleanup");service.onStartCommand(start("T40"),0,2);replacement.recoverRelease.countDown();barrier();
   check(replacement.starts==1&&replacement.profile.equals("T40")&&ProxyService.activeId.equals("T40"),"Stale restore replaced user's newer Apply");stopAndWait(service,3);service.onDestroy();barrier();
  });
  test("destroying foreground owner cancels a pending automatic restore",()->{
   ProxyService service=new ProxyService();service.onCreate();startAndWait(service,"T20",1);Shizuku.available=false;Shizuku.target.die();await(()->ProxyService.state.equals("Нет связи с движком"));
   service.onDestroy();barrier();FakeTransport replacement=new FakeTransport();replacement.recoverable=true;Shizuku.target=replacement;Shizuku.available=true;Thread.sleep(900);barrier();
   check(replacement.starts==0&&!ProxyService.hasRunRequest(),"Destroyed foreground service restarted engine");PacketBridge.stop();
  });
  test("repeated Stop preserves one generation and completes the latest service start ID",()->{
   ProxyService service=new ProxyService();service.onCreate();startAndWait(service,"T20",1);Shizuku.available=false;Shizuku.target.die();service.onStartCommand(stop(),0,2);barrier();
   Field generation=ProxyService.class.getDeclaredField("generation");generation.setAccessible(true);long token=generation.getLong(service);
   for(int i=3;i<=30;i++)service.onStartCommand(stop(),0,i);
   check(generation.getLong(service)==token&&!ProxyService.hasRunRequest(),"Repeated Stop spawned another generation");
   FakeTransport replacement=new FakeTransport();replacement.recoverable=true;Shizuku.target=replacement;Shizuku.available=true;await(()->!PacketBridge.hasPendingCleanup());barrier();
   check(android.app.Service.lastStoppedStartId==30&&replacement.starts==0,"Latest Stop could not finish foreground service");service.onDestroy();barrier();
  });
  test("actual native error remains visible after onDestroy and is not automatically restarted",()->{
   ProxyService service=new ProxyService();service.onCreate();startAndWait(service,"T20",1);FakeTransport target=Shizuku.target;target.state="ERROR";target.nativeAlive=false;
   await(()->ProxyService.state.equals("Ошибка"));String reason=ProxyService.failure;check(!reason.isEmpty()&&!ProxyService.hasRunRequest(),"Native error lost its reason");
   service.onDestroy();barrier();check(ProxyService.state.equals("Ошибка")&&ProxyService.failure.equals(reason)&&target.starts==1,"onDestroy erased the error or restarted native");
  });
  test("ready worker restores after ADB disconnects during cleanup",()->{
   ProxyService service=new ProxyService();service.onCreate();startAndWait(service,"T20",1);Shizuku.available=false;Shizuku.target.die();await(()->ProxyService.state.equals("Нет связи с движком"));
   FakeTransport replacement=new FakeTransport();replacement.recoverable=true;replacement.dropBrokerAfterRecovery=true;Shizuku.target=replacement;Shizuku.available=true;
   await(()->ProxyService.running&&replacement.starts==1);check(!Shizuku.available&&ProxyService.activeId.equals("T20"),"Restore unnecessarily required a live ADB connection");stopAndWait(service,2);service.onDestroy();barrier();
  });
  test("lost restore-start reply with a live worker is cleaned and stays visible",()->{
   ProxyService service=new ProxyService();service.onCreate();startAndWait(service,"T20",1);Shizuku.available=false;Shizuku.target.die();await(()->ProxyService.state.equals("Нет связи с движком"));
   FakeTransport replacement=new FakeTransport();replacement.recoverable=true;replacement.dropBrokerOnStartOnce=true;Shizuku.target=replacement;Shizuku.available=true;
   await(()->ProxyService.state.equals("Ошибка"));check(!replacement.nativeAlive&&!ProxyService.hasRunRequest()&&!PacketBridge.hasPendingCleanup(),"Failed start left untracked native resources");service.onDestroy();barrier();
  });
  test("foreign active replacement helper is never stopped or overwritten by restore",()->{
   ProxyService service=new ProxyService();service.onCreate();startAndWait(service,"T20",1);Shizuku.available=false;Shizuku.target.die();await(()->ProxyService.state.equals("Нет связи с движком"));
   FakeTransport replacement=new FakeTransport();replacement.instance=UUID.randomUUID().toString();replacement.profile="T40";replacement.state="RUNNING";replacement.nativeAlive=true;Shizuku.target=replacement;Shizuku.available=true;
   await(()->ProxyService.state.equals("Ошибка"));check(replacement.starts==0&&replacement.stops==0&&PacketBridge.hasPendingCleanup(),"Foreign helper was stopped or overwritten");service.onDestroy();barrier();
  });
  test("log drain error does not invalidate confirmed Stop",()->{
   PacketBridge.start("T1",1080,false);Shizuku.target.drainFails=true;PacketBridge.stop();check(!PacketBridge.hasPendingCleanup(),"Optional log drain invalidated receipt");
  });
  test("late cancel is scoped to original request",()->{
   PacketBridge.start("T1",1080,false);FakeTransport t=Shizuku.target;CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);t.cancelEntered=entered;t.cancelRelease=release;
   PacketBridge.cancelPending();check(entered.await(2,TimeUnit.SECONDS),"Cancel not started");PacketBridge.stop();PacketBridge.start("T20",1080,false);String id=t.instance;release.countDown();
   await(()->t.foreignStops==1);check(t.state.equals("RUNNING")&&t.instance.equals(id),"Old cancel stopped new run");PacketBridge.stop();
  });
  for(boolean throwing:new boolean[]{false,true})test("production ProxyService ignores stale monitor "+(throwing?"error":"response")+" after Apply",()->{
   ProxyService service=new ProxyService();service.onCreate();startAndWait(service,"T1",1);FakeTransport t=Shizuku.target;CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);t.monitorEntered=entered;t.monitorRelease=release;t.monitorThrows=throwing;
   check(entered.await(2,TimeUnit.SECONDS),"Monitor did not enter status RPC");startAndWait(service,"T20",2);String current=ProxyService.activeInstance;release.countDown();Thread.sleep(100);barrier();
   check(ProxyService.running&&ProxyService.activeId.equals("T20")&&ProxyService.activeInstance.equals(current)&&ProxyService.failure.isEmpty(),"Old monitor poisoned new profile");
   stopAndWait(service,3);stopAndWait(service,4);startAndWait(service,"T1",5);stopAndWait(service,6);service.onDestroy();barrier();check(Shizuku.removes==0,"Apply destroyed helper");
  });
  test("Stop during pending start cancels only that request and can restart",()->{
   ProxyService s=new ProxyService();s.onCreate();FakeTransport t=Shizuku.target;CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);t.startEntered=entered;t.startRelease=release;
   s.onStartCommand(start("T1"),0,1);check(entered.await(2,TimeUnit.SECONDS),"Start RPC not pending");s.onStartCommand(stop(),0,2);await(()->t.stops>0);release.countDown();barrier();
   check(!ProxyService.running&&ProxyService.failure.isEmpty()&&!PacketBridge.hasPendingCleanup(),"Pending start left permanent Stop error");t.startEntered=null;startAndWait(s,"T20",3);stopAndWait(s,4);s.onDestroy();barrier();
  });
  test("destroyed foreground owner cannot stop a later owner",()->{
   ProxyService old=new ProxyService();old.onCreate();startAndWait(old,"T1",1);
   CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);worker.submit(()->{blocked.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}});check(blocked.await(1,TimeUnit.SECONDS),"Worker not blocked");
   old.onDestroy();ProxyService next=new ProxyService();next.onCreate();next.onStartCommand(start("T20"),0,2);release.countDown();barrier();check(ProxyService.running&&ProxyService.activeId.equals("T20"),"Destroyed owner stopped new service");stopAndWait(next,3);next.onDestroy();barrier();
  });
  test("production helper rechecks instance after delayed old cancellation",()->{
   NativePacketService n=new NativePacketService(new Context());String old=UUID.randomUUID().toString(),next=UUID.randomUUID().toString();TapSession oldTap=new TapSession(new Context()),newTap=new TapSession(new Context());
   CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);oldTap.cancelEntered=entered;oldTap.cancelRelease=release;set(n,"instance",old);set(n,"state","RUNNING");set(n,"tap",oldTap);
   FutureTask<String> late=new FutureTask<>(()->n.stopProxy(old));new Thread(late,"old-native-stop").start();check(entered.await(1,TimeUnit.SECONDS),"Old cancellation not blocked");
   set(n,"instance",next);set(n,"tap",newTap);set(n,"cancelled",false);release.countDown();late.get(2,TimeUnit.SECONDS);
   check(newTap.cancels==0&&newTap.closes==0&&new JSONObject(n.status()).getString("state").equals("RUNNING"),"Late old stop destroyed new TAP");n.stopProxy(next);
   for(Thread t:Thread.getAllStackTraces().keySet())if(t.getName().equals("packet-idle-exit"))t.interrupt();
  });
  test("delayed old owner-death callback cannot stop a replacement app owner",()->{
   NativePacketService n=new NativePacketService(new Context());String old=UUID.randomUUID().toString(),next=UUID.randomUUID().toString();
   Binder oldOwner=new Binder(),nextOwner=new Binder();TapSession oldTap=new TapSession(new Context()),newTap=new TapSession(new Context());
   CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);oldTap.cancelEntered=entered;oldTap.cancelRelease=release;
   set(n,"instance",old);set(n,"ownerToken",oldOwner);set(n,"state","RUNNING");set(n,"tap",oldTap);
   FutureTask<Void> late=new FutureTask<>(()->{n.ownerLost(old,oldOwner);return null;});new Thread(late,"old-owner-death").start();
   check(entered.await(1,TimeUnit.SECONDS),"Old owner cancellation not blocked");
   set(n,"instance",next);set(n,"ownerToken",nextOwner);set(n,"tap",newTap);set(n,"cancelled",false);release.countDown();late.get(2,TimeUnit.SECONDS);Thread.sleep(30);
   check(newTap.cancels==0&&newTap.closes==0&&new JSONObject(n.status()).getString("state").equals("RUNNING"),"Old owner callback affected new run");
   n.stopProxy(next);for(Thread t:Thread.getAllStackTraces().keySet())if(t.getName().equals("packet-idle-exit"))t.interrupt();
  });
  test("production NativePacketService rejects foreign delayed stop and closes matching TAP",()->{
   NativePacketService n=new NativePacketService(new Context());String own=UUID.randomUUID().toString();TapSession tap=new TapSession(new Context());
   set(n,"instance",own);set(n,"state","RUNNING");set(n,"tap",tap);set(n,"error","old display error");
   n.stopProxy(UUID.randomUUID().toString());check(tap.cancels==0&&tap.closes==0,"Foreign request touched TAP");
   check(new JSONObject(n.status()).getString("state").equals("RUNNING"),"Foreign request stopped current engine");
   JSONObject stopped=new JSONObject(n.stopProxy(own));check(tap.cancels==1&&tap.closes==1,"Owned TAP not cleaned exactly once");check(stopped.getString("state").equals("STOPPED")&&stopped.getString("error").isEmpty(),"Matching stop not confirmed");
   for(Thread t:Thread.getAllStackTraces().keySet())if(t.getName().equals("packet-idle-exit"))t.interrupt();
  });
  test("production helper verifies orphan cleanup and refuses recovery over active TAP",()->{
   java.io.File root=java.nio.file.Files.createTempDirectory("igareck-native-recovery-").toFile();String old=UUID.randomUUID().toString(),epoch=UUID.randomUUID().toString();
   java.io.File dir=RecoveryHarness.legacy(root,old);NativePacketService n=new NativePacketService(new Context());
   PacketRecovery recovery=new PacketRecovery(root,10364,UUID.randomUUID().toString(),new RecoveryHarness.Host());set(n,"recovery",recovery);
   JSONObject proof=new JSONObject(n.recoverProxy(old,epoch));check(proof.getJSONObject("recovery").getBoolean("verified")&&!dir.exists(),"Helper did not verify orphan");
   TapSession active=new TapSession(new Context());set(n,"tap",active);set(n,"state","RUNNING");set(n,"instance",UUID.randomUUID().toString());
   JSONObject denied=new JSONObject(n.recoverProxy(old,epoch));check(denied.getString("state").equals("RUNNING")&&!denied.getJSONObject("recovery").optBoolean("verified")&&active.closes==0,"Recovery touched current TAP");
   n.stopProxy(denied.getString("instance"));recovery.close();
   for(Thread t:Thread.getAllStackTraces().keySet())if(t.getName().equals("packet-idle-exit"))t.interrupt();
  });
  test("Stop and lease expiry release the real resource lock while the old helper is retained",()->{
   for(boolean ownerIdle:new boolean[]{false,true}){
    java.io.File root=RecoveryHarness.root();
    NativePacketService first=new NativePacketService(new Context(),true);
    String epoch=new JSONObject(first.status()).getString("service_epoch"),owned=UUID.randomUUID().toString();
    PacketRecovery recovery=RecoveryHarness.manager(root,epoch,new RecoveryHarness.Host());
    java.io.File work=recovery.begin(owned);
    set(first,"recovery",recovery);set(first,"work",work);set(first,"instance",owned);set(first,"tap",new TapSession(new Context()));
    NativePacketService replacement=new NativePacketService(new Context(),true);
    String nextEpoch=new JSONObject(replacement.status()).getString("service_epoch");
    set(replacement,"recovery",RecoveryHarness.manager(root,nextEpoch,new RecoveryHarness.Host()));
    check(!new JSONObject(replacement.recoverProxy(owned,epoch)).getJSONObject("recovery").optBoolean("verified"),"New helper took over a live lock");
    if(ownerIdle)first.releaseOwner();else first.stopProxy(owned);
    check(!work.exists()&&new JSONObject(first.status()).getString("state").equals("STOPPED"),"Old worker retained owned resources");
    check(new JSONObject(replacement.recoverProxy(owned,epoch)).getJSONObject("recovery").getBoolean("verified"),"Idle helper still holds the lock after cleanup");
    PacketRecovery third=RecoveryHarness.manager(root,UUID.randomUUID().toString(),new RecoveryHarness.Host());
    try{check(third.sweep(null,null).getBoolean("verified"),"Recovery retained a lock after verification");}finally{third.close();}
   }
  });
  test("production service replaces child without replacing its TAP and Lua workspace",()->{
   java.io.File root=RecoveryHarness.root();
   java.nio.file.Path assets=java.nio.file.Path.of(System.getProperty("igareck.test.assets"));
   java.nio.file.Path apk=root.toPath().resolve("fixture.apk");
   try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(apk));
       java.util.stream.Stream<java.nio.file.Path> paths=java.nio.file.Files.walk(assets)){
    for(java.nio.file.Path path:paths.filter(java.nio.file.Files::isRegularFile).toList()){
     zip.putNextEntry(new java.util.zip.ZipEntry("assets/"+assets.relativize(path)));java.nio.file.Files.copy(path,zip);zip.closeEntry();
    }
   }
   java.nio.file.Path executable=root.toPath().resolve("libnfqws2.so");
   java.nio.file.Files.writeString(executable,"""
       #!/bin/sh
       udp=false
       for arg in "$@"; do
         case "$arg" in
           --igareck-instance=*) instance=${arg#*=} ;;
           --igareck-udp=1) udp=true ;;
           --igareck-quic=*) quic=${arg#*=} ;;
           --igareck-discord=*) discord=${arg#*=} ;;
         esac
       done
       printf '[IGARECK_LISTEN_READY] udp_associate=%s quic=%s discord=%s instance=%s\n' "$udp" "$quic" "$discord" "$instance"
       while read -r command; do :; done
       """);
   check(executable.toFile().setExecutable(true),"Native fixture permissions");
   String previousNative=System.getProperty("igareck.test.native");
   System.setProperty("igareck.test.native",root.getPath());System.setProperty("igareck.test.apk",apk.toString());
   android.system.Os.packetSocketReady=true;
   NativePacketService worker=new NativePacketService(new Context(),true);
   String own=UUID.randomUUID().toString();
   String epoch=new JSONObject(worker.status()).getString("service_epoch");
   set(worker,"recovery",RecoveryHarness.manager(root,epoch,new RecoveryHarness.Host()));
   IBinder token=new Binder();
   int transfers=android.net.LocalSocket.descriptorTransfers;
   Field tapField=NativePacketService.class.getDeclaredField("tap");tapField.setAccessible(true);
   Field childField=NativePacketService.class.getDeclaredField("child");childField.setAccessible(true);
   Field workField=NativePacketService.class.getDeclaredField("work");workField.setAccessible(true);
   try{
    JSONObject cold=new JSONObject(worker.startProxy("T1",1080,false,token,own));
    check(cold.getString("state").equals("RUNNING"),"Cold fixture start "+cold.optString("error"));
    TapSession tap=(TapSession)tapField.get(worker);
    Object work=workField.get(worker);
    java.lang.Process child=(java.lang.Process)childField.get(worker);
    JSONObject warm=new JSONObject(worker.startProxyUdpDiscord("T40",1080,false,token,own,true,"Q3","D1"));
    check(warm.getString("state").equals("RUNNING")&&warm.getString("profile").equals("T40")&&warm.getString("discord_profile").equals("D1"),"Warm fixture start "+warm.optString("error"));
    check(!child.isAlive()&&child!=childField.get(worker),"Old native child survived Apply");
    check(tap==tapField.get(worker)&&work==workField.get(worker)&&tap.opens==1&&tap.closes==0,"Apply recreated owned TAP or workspace");
    check(android.net.LocalSocket.descriptorTransfers==transfers+2,"TAP descriptor was not handed to both native starts");
    worker.stopProxy(own);
    JSONObject delayed=new JSONObject(worker.startProxy("T40",1080,false,token,own));
    check(!delayed.getBoolean("native_alive")&&tap.closes==1&&tapField.get(worker)==null,"Delayed same-instance Apply reopened a stopped run");
   }finally{
    worker.stopProxy(own);android.system.Os.packetSocketReady=false;
    System.clearProperty("igareck.test.apk");System.setProperty("igareck.test.native",previousNative);
   }
  });
  test("Apply retains the pending instance on a verified reusable helper",()->{
   Shizuku.target.warmApply=true;
   ProxyService service=new ProxyService();service.onCreate();
   service.onStartCommand(start("T20"),0,1);barrier();
   String first=ProxyService.activeInstance;
   service.onStartCommand(start("T40").putExtra("udp",true).putExtra("quic","Q3").putExtra("discord","D1"),0,2);barrier();
   check(first.equals(ProxyService.activeInstance)&&Shizuku.target.stops==0&&Shizuku.target.starts==2,"Apply tore down the packet session");
   check(ProxyService.running&&ProxyService.activeId.equals("T40")&&ProxyService.activeQuicId.equals("Q3")&&ProxyService.activeDiscordId.equals("D1"),"Warm settings were not confirmed");
   stopAndWait(service,3);check(!PacketBridge.hasPendingCleanup(),"Warm Apply left no usable Stop");
   service.onDestroy();barrier();
  });
  test("UDP applied Q1-Q5 is independent from TLS, port and verbose",()->{
   ProxyService service=new ProxyService();service.onCreate();
   for(int i=1;i<=5;i++){
    service.onStartCommand(start(i%2==0?"T40":"T20").putExtra("udp",true).putExtra("quic","Q"+i).putExtra("discord","D"+(i+1)).putExtra("port",12080),0,i);barrier();
    check(ProxyService.running&&ProxyService.activeUdp&&ProxyService.activeQuicId.equals("Q"+i),"UDP selection not applied");
    check(ProxyService.activeDiscordId.equals("D"+(i+1))&&Shizuku.target.lastDiscord.equals("D"+(i+1)),"Discord selection not applied");
    check(Shizuku.target.lastUdp&&Shizuku.target.lastQuic.equals("Q"+i),"RPC lost UDP settings");
   }stopAndWait(service,6);service.onDestroy();barrier();
  });
  test("actual helper replacement restores applied UDP and Q4",()->{
   ProxyService service=new ProxyService();service.onCreate();
   service.onStartCommand(start("T20").putExtra("udp",true).putExtra("quic","Q4").putExtra("discord","D8"),0,1);barrier();
   Shizuku.available=false;Shizuku.target.die();await(()->ProxyService.state.equals("Нет связи с движком"));
   FakeTransport replacement=new FakeTransport();replacement.recoverable=true;Shizuku.target=replacement;Shizuku.available=true;
   await(()->replacement.starts==1&&ProxyService.running);
   check(replacement.lastUdp&&replacement.lastQuic.equals("Q4")&&ProxyService.activeQuicId.equals("Q4"),"UDP restore lost applied Q");
   check(replacement.lastDiscord.equals("D8")&&ProxyService.activeDiscordId.equals("D8"),"UDP restore lost applied D8");
   stopAndWait(service,2);service.onDestroy();barrier();
  });
  test("UDP Stop before helper return cancels restoration",()->{
   ProxyService service=new ProxyService();service.onCreate();
   service.onStartCommand(start("T40").putExtra("udp",true).putExtra("quic","Q2").putExtra("discord","D7"),0,1);barrier();
   Shizuku.available=false;Shizuku.target.die();await(()->ProxyService.state.equals("Нет связи с движком"));
   service.onStartCommand(stop(),0,2);barrier();FakeTransport replacement=new FakeTransport();replacement.recoverable=true;Shizuku.target=replacement;Shizuku.available=true;
   await(()->!PacketBridge.hasPendingCleanup());barrier();check(replacement.starts==0&&!ProxyService.hasRunRequest(),"Stop resurrected UDP");service.onDestroy();barrier();
  });
  test("UDP can be disabled on Apply while preserving selected Q",()->{
   ProxyService service=new ProxyService();service.onCreate();
   service.onStartCommand(start("T20").putExtra("udp",true).putExtra("quic","Q5"),0,1);barrier();
   service.onStartCommand(start("T20").putExtra("udp",false).putExtra("quic","Q5"),0,2);barrier();
   check(ProxyService.running&&!ProxyService.activeUdp&&!Shizuku.target.lastUdp&&ProxyService.activeQuicId.equals("Q5"),"Disable UDP lost TCP/Q setting");stopAndWait(service,3);service.onDestroy();barrier();
  });
  test("unknown QUIC profile cannot create native resources",()->{
   expectFailure(()->PacketBridge.start("T20",1080,false,true,"Q6"));check(Shizuku.target.starts==0&&!PacketBridge.hasPendingCleanup(),"Invalid Q created resources");
  });
  test("unknown Discord profile cannot create native resources",()->{
   expectFailure(()->PacketBridge.start("T20",1080,false,true,"Q1","D11"));check(Shizuku.target.starts==0&&!PacketBridge.hasPendingCleanup(),"Invalid D created resources");
  });
 }
 public static void main(String[] args)throws Exception{
  try{if(args.length>0&&args[0].equals("baseline")){oldBaseline();return;}run();System.out.println(new JSONObject().put("passed",true).put("tests",results).put("count",results.length()).put("scope","Actual PacketBridge, ProxyService and NativePacketService Java. Android/Binder/embedded ADB/TAP boundaries are host fixtures; no on-device certification."));}
  catch(Throwable e){System.err.println("Completed checks: "+results.toString(2));e.printStackTrace();System.exit(1);}
  finally{worker.shutdownNow();Handler.MAIN.shutdownNow();}
 }
}
