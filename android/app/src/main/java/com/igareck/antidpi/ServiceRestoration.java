/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;

import android.app.job.*;
import android.content.*;
import android.net.*;
import android.os.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Restore only the privileged helper. Never starts proxy strategies or requests permissions. */
final class ServiceRestoration {
 static final int RETRY_JOB=9401, WATCH_JOB=9402;
 static final long RETRY_DELAY=60000, ATTEMPT_LIMIT=120000;
 private static final long USB_QUIET_MS=12000;
 private static volatile long usbQuietUntil;
 private static final Object LOCK=new Object();
 private static final ExecutorService WORK=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"service-restoration");t.setDaemon(true);return t;});
 private static volatile Attempt current;
 private static volatile String state="Ожидание",reason="",lastOrigin="";
 private static volatile long attempts,lastAt;
 private static ConnectivityManager.NetworkCallback callback;
 interface Done {void finish(boolean retry);}
 static final class Attempt {
  volatile boolean cancelled;
  final long deadline=SystemClock.elapsedRealtime()+ATTEMPT_LIMIT;
  void cancel(){cancelled=true;}
  boolean wanted(Context c){return !cancelled&&SystemClock.elapsedRealtime()<deadline&&eligible(c);}
 }
 static boolean eligible(Context c){return AutoActivation.restoreWanted(c)&&AutoActivation.established(c);}
 static boolean launchReady(Context c){
  return EmbeddedAdb.paired()&&EmbeddedAdb.networkReady(c);
 }
 static void usbConfigurationChanged(){usbQuietUntil=SystemClock.elapsedRealtime()+USB_QUIET_MS;}
 private static long usbDelay(){return Math.max(0,usbQuietUntil-SystemClock.elapsedRealtime());}
 static boolean usbSettling(){return usbDelay()>0;}
 static void initialize(Context c){
  EmbeddedAdb.init(c);
  synchronized(LOCK){if(callback==null)try{
   ConnectivityManager cm=c.getSystemService(ConnectivityManager.class);
   ConnectivityManager.NetworkCallback own=new ConnectivityManager.NetworkCallback(){
    @Override public void onAvailable(Network n){scheduleRetry(c,0);}
   };
   cm.registerNetworkCallback(wifiRequest(),own);callback=own;
  }catch(Exception e){AppLog.line("SERVICE_RESTORE","Network observer unavailable: "+e.getClass().getSimpleName());}}
  reconcile(c);
 }
 static NetworkRequest wifiRequest(){return new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();}
 static void activated(Context c){if(c==null)return;AutoActivation.markEstablished(c);state="Сетевой модуль активен";reason="";reconcile(c);}
 static void manual(Context c){cancelCurrent();AutoActivation.setRestorePaused(c,false);reconcile(c);}
 static void pause(Context c){AutoActivation.setRestorePaused(c,true);cancelCurrent();cancelJobs(c);state="Приостановлено пользователем";reason="Возобновится после ручного запуска модуля или стратегии.";AppLog.line("SERVICE_RESTORE","Paused explicitly by user");}
 static void optionsChanged(Context c){cancelCurrent();cancelJobs(c);reconcile(c);}
 static void cancelCurrent(){Attempt a=current;if(a!=null)a.cancel();}
 static void visible(Context c){reconcile(c);requestForeground(c,"app_open");}
 static void boot(Context c,String origin){
  AppLog.line("SERVICE_RESTORE","Received "+origin+"; restore strategies=false");
  reconcile(c);
  if("package_replaced".equals(origin)){
   try{JobScheduler js=c.getSystemService(JobScheduler.class);if(js!=null)js.cancel(RETRY_JOB);}catch(Exception ignored){}
   scheduleRetry(c,45000);return; // The old APK worker observes replacement within 30 seconds.
  }
  scheduleRetry(c,1000);requestForeground(c,origin);
 }
 static void requestForeground(Context c,String origin){
  if(!eligible(c)||PacketBridge.hasActiveWorker()||EmbeddedAdb.busy()||EmbeddedAdb.searching()||current!=null||!launchReady(c))return;
  try{c.startForegroundService(new Intent(c,RestorationService.class).putExtra("origin",origin));}
  catch(Exception e){state="Ожидается фоновая попытка";reason="Android отложил запуск; можно открыть приложение.";AppLog.line("SERVICE_RESTORE","Foreground start deferred: "+e.getClass().getSimpleName());scheduleRetry(c,RETRY_DELAY);}
 }
 static void workerLinkLost(Context owner){
  Context c=owner.getApplicationContext();
  if(!eligible(c)||PacketBridge.hasActiveWorker()||ProxyService.hasRunRequest())return;
  if(launchReady(c)){
   scheduleRetry(c,1500);
   requestForeground(c,"worker_link_lost");
   return;
  }
  WORK.execute(()->{
   if(!eligible(c)||PacketBridge.hasActiveWorker()||EmbeddedAdb.busy()||EmbeddedAdb.searching()
       ||current!=null||ProxyService.hasRunRequest())return;
   if(PacketBridge.reattachOnly()){state="Сетевой модуль активен";reason="";return;}
   if(launchReady(c)){
    scheduleRetry(c,1500);
    requestForeground(c,"worker_link_lost");
   }else{
    state="Сетевой модуль недоступен";
    reason="Подключитесь к Wi-Fi или запустите модуль через USB.";
   }
  });
 }
 static Attempt begin(Context owner,String origin,Done done){
  Context c=owner.getApplicationContext();final Attempt attempt=new Attempt();
  synchronized(LOCK){if(current!=null||!eligible(c))return null;current=attempt;}
  WORK.execute(()->{
   boolean retry=false;PowerManager.WakeLock wake=null;
   try{
    if(!attempt.wanted(c))return;
    if(EmbeddedAdb.busy()||EmbeddedAdb.searching()||ProxyService.hasRunRequest()){retry=true;return;}
    lastOrigin=origin;lastAt=System.currentTimeMillis();state="Проверяется сетевой модуль";reason="";
    // An existing USB/ADB helper can be reused even when Wi-Fi is absent.
    if(PacketBridge.reattachOnly()){state="Сетевой модуль активен";return;}
    if(!attempt.wanted(c))return;
    if(!launchReady(c)){
     state="Ожидание условий запуска";
     reason=!EmbeddedAdb.paired()?"Для автоматического запуска после перезагрузки нужно однократно выполнить Wi-Fi-сопряжение.":!AutoActivation.wifiConnected(c)?"Ожидается подключение к Wi-Fi. Для запуска без Wi-Fi доступен USB.":"Проверь разрешение беспроводной отладки для текущей Wi-Fi-сети.";
     return;
    }
    PowerManager pm=c.getSystemService(PowerManager.class);
    if(pm!=null){wake=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"IgareckAntiDPI:ServiceRestore");wake.setReferenceCounted(false);wake.acquire(ATTEMPT_LIMIT+5000);}
    if(usbSettling()){
     state="Ожидается завершение настройки USB";
     AppLog.line("SERVICE_RESTORE","Automatic activation waiting for USB configuration");
     for(long delay=usbDelay();delay>0&&attempt.wanted(c)&&launchReady(c);delay=usbDelay())
      Thread.sleep(Math.min(200,delay));
     if(!attempt.wanted(c))return;
     if(!launchReady(c)){
      state="Ожидание условий запуска";reason="Подключитесь к Wi-Fi или запустите модуль через USB.";
      return;
     }
     if(PacketBridge.reattachOnly()){state="Сетевой модуль активен";return;}
    }
    attempts++;state="Восстанавливается сетевой модуль";AppLog.line("SERVICE_RESTORE","Attempt "+attempts+"; origin="+origin);
    PacketBridge.restoreAutomatically(()->attempt.wanted(c));
    if(attempt.wanted(c)){state="Сетевой модуль активен";reason="";AppLog.line("SERVICE_RESTORE","Helper restored; strategies remain stopped unless already requested");}
   }catch(CancellationException e){state="Восстановление отменено";}
   catch(Exception e){retry=attempt.wanted(c);state="Повторная попытка отложена";reason=e.getClass().getSimpleName();AppLog.line("SERVICE_RESTORE","Attempt deferred: "+reason);}
   finally{
    if(wake!=null&&wake.isHeld())wake.release();
    synchronized(LOCK){if(current==attempt)current=null;}
    if(!eligible(c)){retry=false;state=AutoActivation.restorePaused(c)?"Приостановлено пользователем":"Автовосстановление выключено";}
    done.finish(retry);
   }
  });return attempt;
 }
 static void reconcile(Context c){
  if(!eligible(c)){cancelJobs(c);return;}
  try{
   JobScheduler js=c.getSystemService(JobScheduler.class);if(js==null)return;
   if(js.getPendingJob(WATCH_JOB)==null){
    JobInfo.Builder b=builder(c,WATCH_JOB).setPeriodic(15*60000L,5*60000L);
    b.setRequiredNetwork(wifiRequest());
    if(js.schedule(b.build())!=JobScheduler.RESULT_SUCCESS)AppLog.line("SERVICE_RESTORE","Periodic recovery scheduling rejected");
   }
  }catch(Exception e){AppLog.line("SERVICE_RESTORE","Periodic scheduling unavailable: "+e.getClass().getSimpleName());}
 }
 static JobInfo.Builder builder(Context c,int id){return new JobInfo.Builder(id,new ComponentName(c,RestorationJobService.class)).setPersisted(true).setBackoffCriteria(RETRY_DELAY,JobInfo.BACKOFF_POLICY_EXPONENTIAL);}
 static void scheduleRetry(Context c,long delay){
  if(!eligible(c)||!EmbeddedAdb.paired())return;
  try{
   JobScheduler js=c.getSystemService(JobScheduler.class);if(js==null||js.getPendingJob(RETRY_JOB)!=null)return;
   JobInfo.Builder b=builder(c,RETRY_JOB).setMinimumLatency(delay);
   b.setRequiredNetwork(wifiRequest());
   if(js.schedule(b.build())!=JobScheduler.RESULT_SUCCESS)AppLog.line("SERVICE_RESTORE","Deferred recovery scheduling rejected");
  }catch(Exception e){AppLog.line("SERVICE_RESTORE","Retry scheduling unavailable: "+e.getClass().getSimpleName());}
 }
 static void cancelJobs(Context c){try{JobScheduler js=c.getSystemService(JobScheduler.class);if(js!=null){js.cancel(RETRY_JOB);js.cancel(WATCH_JOB);}}catch(Exception e){AppLog.line("SERVICE_RESTORE","Cannot cancel pending jobs: "+e.getClass().getSimpleName());}}
 static String describe(Context c){
  if(!AutoActivation.restoreEnabled(c))return "Автовосстановление выключено.";
  if(AutoActivation.restorePaused(c))return "Автовосстановление приостановлено кнопкой «Остановить сетевой модуль». Ручной запуск возобновит его.";
  if(!AutoActivation.established(c))return "Автовосстановление будет доступно после первой успешной активации.";
  if(PacketBridge.hasActiveWorker())return "Автовосстановление включено · сетевой модуль активен.";
  if(state.equals("Сетевой модуль активен"))return "Автовосстановление включено · сетевой модуль недоступен.";
  return "Автовосстановление включено · "+state+(reason.isEmpty()?"":". "+reason);
 }
 static String snapshot(Context c){try{return new JSONObject().put("enabled",AutoActivation.restoreEnabled(c)).put("paused",AutoActivation.restorePaused(c)).put("established",AutoActivation.established(c)).put("state",state).put("reason",reason).put("origin",lastOrigin).put("attempts_this_process",attempts).put("last_attempt_unix_ms",lastAt).put("attempt_running",current!=null).put("restores_strategies",false).toString();}catch(Exception e){return "{}";}}
 private ServiceRestoration(){}
}
