/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import android.content.*;
import android.os.*;
import java.io.IOException;
import java.net.ConnectException;
import java.util.function.BooleanSupplier;
import java.util.UUID;
import java.util.concurrent.*;
import org.json.*;

/** Worker-thread IPC; binding callbacks and resource receipts have separate lifetimes. */
public final class PacketBridge {
 static final class CleanupPendingException extends IOException {
  CleanupPendingException(Exception cause){super(cause.getMessage(),cause);}
 }
 private static final IBinder OWNER=new Binder();
 private static final Object STATE=new Object(),CONNECT=new Object();
 private static volatile PacketWorkerTransport worker;
 private static volatile String workerDescriptor="";
 private static volatile String cached="{\"state\":\"NOT_CONNECTED\"}";
 private static long launchNumber,revision;
 private static boolean cleanupPending;
 private static String pendingInstance="",pendingEpoch="",pendingWorker="",cancelRequestedFor="",stopReceipt="";
 private static String lastControlFailure="";
 private static long lastControlFailureAt;
 private static long launchRetryAfter;
 private static SharedPreferences preferences;
 private static String installedApk="";
 private static volatile Context appContext;
 public static void init(Context context){EmbeddedAdb.init(context);synchronized(STATE){
  if(preferences!=null)return;
  appContext=context.getApplicationContext();
  preferences=context.getSharedPreferences("packet_resource_ownership",Context.MODE_PRIVATE);
  installedApk=context.getApplicationInfo().sourceDir;
  String saved=preferences.getString("worker","");
  if(installedApk.equals(preferences.getString("worker_apk","")))workerDescriptor=saved;
  else if(!saved.isEmpty())AppLog.line("PACKET_CONTROL","Saved worker belongs to an older installation; fresh activation required");
  String record=preferences.getString("pending","");
  if(!record.isEmpty())try{
   JSONObject value=new JSONObject(record);pendingInstance=PacketRecovery.canonicalId(value.getString("instance"));
   pendingEpoch=PacketRecovery.canonicalId(value.getString("epoch"));
   pendingWorker=value.optString("worker",workerDescriptor);cleanupPending=true;
  }catch(Exception e){cleanupPending=true;pendingInstance="";pendingEpoch="";AppLog.line("PACKET_RECOVERY","Повреждена запись владения ресурсами; экспортируй журнал.");}
 }}
 private static void persist(String instance,String epoch,String descriptor)throws Exception {
  if(preferences!=null&&!preferences.edit().remove("verified_stop").putString("pending",instance.isEmpty()?"":new JSONObject().put("instance",instance).put("epoch",epoch).put("worker",descriptor).toString()).commit())
   throw new IOException("Не удалось сохранить состояние собственных пакетных ресурсов.");
 }
 static void recordVerifiedStop(Context context,String instance,String epoch,String proof){
  try{
   PacketRecovery.canonicalId(instance);PacketRecovery.canonicalId(epoch);
   init(context);
   synchronized(STATE){
    if(!cleanupPending||!pendingInstance.equals(instance)||!pendingEpoch.equals(epoch))return;
    if(!StopVerifier.authentic(pendingWorker,android.os.Process.myUid(),instance,epoch,proof))return;
    if(!preferences.edit().putString("verified_stop",proof).commit())throw new IOException("Cannot persist independent Stop receipt");
   }
   AppLog.line("PACKET_STOP_VERIFIER","Independent verification received; instance="+instance);
  }catch(Exception e){AppLog.line("PACKET_STOP_VERIFIER","Receipt unavailable: "+e.getClass().getSimpleName());}
 }
 private static String independentStopReceipt(String instance,String epoch,String descriptor){
  synchronized(STATE){
   if(preferences==null||!cleanupPending||!pendingInstance.equals(instance)||!pendingEpoch.equals(epoch))return null;
   try{
    if(!StopVerifier.authentic(descriptor,android.os.Process.myUid(),instance,epoch,preferences.getString("verified_stop","")))return null;
    return new JSONObject().put("state","STOPPED").put("instance",instance).put("service_epoch",epoch)
     .put("native_alive",false).put("workspace_cleanup_ok",true).put("tap",new JSONObject().put("cleanup_ok",true))
     .put("stop_confirmation","independent_local_verification").toString();
   }catch(Exception e){return null;}
  }
 }
 public static boolean permitted(){return Build.VERSION.SDK_INT>=30&&(live()!=null||EmbeddedAdb.canActivate());}
 public static boolean hasActiveWorker(){return live()!=null;}
 static void linkLost(PacketWorkerTransport owner,String failure){
  if(owner!=worker||owner.available())return;
  AppLog.line("PACKET_CONTROL","Authenticated worker link lost; cause="+failure);
  Context context=appContext;
  if(context!=null)ServiceRestoration.workerLinkLost(context);
 }
 static void linkRestored(PacketWorkerTransport owner){
  if(owner==worker)AppLog.line("PACKET_CONTROL","Authenticated worker link recovered on the same channel");
 }
 public static JSONObject activate()throws Exception{ITransportService target=connect();JSONObject value=new JSONObject(target.status());if(target.getUid()!=2000||value.optInt("uid",-1)!=2000||!AppIdentity.PACKAGE_NAME.equals(value.optString("application_id")))throw new IOException("Не подтверждён сетевой модуль ADB UID 2000");EmbeddedAdb.activated();return value;}
 private static ITransportService live(){
  PacketWorkerTransport w=worker;return w!=null&&w.available()?w:null;
 }
 public static JSONObject activateUsb()throws Exception{ITransportService target=connect(true,true);JSONObject value=new JSONObject(target.status());if(target.getUid()!=2000||value.optInt("uid",-1)!=2000||!AppIdentity.PACKAGE_NAME.equals(value.optString("application_id")))throw new IOException("Не подтверждён сетевой модуль USB ADB UID 2000");EmbeddedAdb.activated();return value;}
 public static void reattachSaved(){new Thread(()->{try{long own=currentRevision();ITransportService t=connect(false,false,true,null);accept(t.status(),own);}catch(Exception ignored){}},"packet-reattach").start();}
 private static ITransportService connect()throws Exception{return connect(true,false);}
 public static boolean reattachOnly(){try{ITransportService t=connect(false,false,true,null);return t.getUid()==2000;}catch(Exception e){return false;}}
 public static void restoreAutomatically(BooleanSupplier keep)throws Exception{
  ITransportService t=connect(true,false,false,keep);
  if(!keep.getAsBoolean())throw new CancellationException();
  JSONObject value=new JSONObject(t.status());
  if(t.getUid()!=2000||value.optInt("uid",-1)!=2000||!AppIdentity.PACKAGE_NAME.equals(value.optString("application_id")))throw new IOException("Не подтверждён сетевой модуль ADB");
 }
 private static ITransportService connect(boolean allowLaunch,boolean usb)throws Exception{return connect(allowLaunch,usb,false,null);}
 private static ITransportService connect(boolean allowLaunch,boolean usb,boolean reattachOnly,BooleanSupplier keep)throws Exception{
  return connect(allowLaunch,usb,reattachOnly,keep,allowLaunch&&keep==null);
 }
 private static ITransportService connect(boolean allowLaunch,boolean usb,boolean reattachOnly,BooleanSupplier keep,boolean userRequested)throws Exception{
  synchronized(CONNECT){
   if(keep!=null&&!keep.getAsBoolean())throw new CancellationException();
   ITransportService t=live();if(t!=null)return t;
   PacketWorkerTransport candidate=null;
   if(!workerDescriptor.isEmpty())try{
    PacketWorkerTransport nextWorker=new PacketWorkerTransport(new JSONObject(workerDescriptor));candidate=nextWorker;
    nextWorker.confirmRetention();
    PacketWorkerTransport previous=worker;worker=nextWorker;if(previous!=null)previous.detach();
    AppLog.line("PACKET_CONTROL","Connected to autonomous shell worker; reused saved channel");EmbeddedAdb.activated();return nextWorker;
   }catch(Exception unavailable){
    if(candidate!=null)candidate.detach();
    AppLog.line("PACKET_CONTROL","Saved worker link check failed: "+unavailable.getClass().getSimpleName());
    // A dead worker still needs strict resource recovery below.
   }
   if(reattachOnly)throw new IOException("Сохранённый сетевой модуль недоступен");
   if(keep!=null&&!keep.getAsBoolean())throw new CancellationException();
   if(!allowLaunch&&(!EmbeddedAdb.backgroundLaunchAllowed()||ServiceRestoration.usbSettling()||SystemClock.elapsedRealtime()<launchRetryAfter))throw new IOException("Сетевой модуль сейчас недоступен. Открой «Активация сетевого модуля»; запрос очистки сохранён.");
   if(!usb&&!EmbeddedAdb.canActivate())throw new IOException("Открой «Активация сетевого модуля» и выполни сопряжение ADB.");
   launchNumber++;String descriptor;
   try{descriptor=usb?EmbeddedAdb.launchUsbWorker():EmbeddedAdb.launchWorker(userRequested,keep==null?()->true:keep);launchRetryAfter=0;}catch(Exception e){launchRetryAfter=SystemClock.elapsedRealtime()+15000;EmbeddedAdb.failed("launch",e);throw e;}
   PacketWorkerTransport nextWorker=new PacketWorkerTransport(new JSONObject(descriptor));
   if(keep!=null&&!keep.getAsBoolean()){nextWorker.detach();throw new CancellationException();}
   // A capability is app-private state. snapshot/export must never include it.
   if(preferences!=null&&!preferences.edit().putString("worker",descriptor).putString("worker_apk",installedApk).commit()){
    nextWorker.detach();throw new IOException("Не удалось сохранить канал управления сетевым модулем");
   }
   try{nextWorker.confirmRetention();}catch(Exception e){nextWorker.detach();throw e;}
   PacketWorkerTransport previous=worker;worker=nextWorker;workerDescriptor=descriptor;if(previous!=null)previous.detach();
   EmbeddedAdb.activated();return nextWorker;
  }
 }
 private static long currentRevision(){synchronized(STATE){return revision;}}
 private static void accept(String value,long expected){synchronized(STATE){if(revision==expected)cached=value;}}
 private static void drainBestEffort(ITransportService target){
  try{JSONArray lines=new JSONArray(target.drainLogs());for(int i=0;i<lines.length();i++){
   String line=lines.getString(i);
   AppLog.line(line.startsWith("[IGARECK_NFQWS2_METRICS]")?"NFQ_METRICS":line.startsWith("[IGARECK_TAP_CLEANUP]")?"TAP_CLEANUP":line.startsWith("[IGARECK_UDP_")||line.startsWith("[IGARECK_DISCORD_")?"UDP_EVENT":"ENGINE",line);
  }}catch(Exception e){AppLog.line("PACKET_LOG","Журнал сетевого модуля недоступен: "+e.getClass().getSimpleName());}
 }
 public static JSONObject start(String profile,int port,boolean verbose)throws Exception{
  return start(profile,port,verbose,false,"Q1");
 }
 public static JSONObject start(String profile,int port,boolean verbose,boolean udp,String quic)throws Exception{
  return start(profile,port,verbose,udp,quic,"D0");
 }
 public static JSONObject start(String profile,int port,boolean verbose,boolean udp,String quic,String discord)throws Exception{
  return start(profile,port,verbose,udp,quic,discord,()->true);
 }
 static JSONObject start(String profile,int port,boolean verbose,boolean udp,String quic,String discord,BooleanSupplier keep)throws Exception{
  return startOwned(TcpIds.canonical(profile),port,verbose,udp,quic,discord,null,keep);
 }
 public static JSONObject startYoutube(int[] numbers)throws Exception{
  return startYoutube(numbers,()->true);
 }
 static JSONObject startYoutube(int[] numbers,BooleanSupplier keep)throws Exception{
  return startOwned("YOUTUBE",0,false,false,"Q1","D0",numbers,keep);
 }
 private static JSONObject startOwned(String profile,int port,boolean verbose,boolean udp,String quic,String discord,int[] probes,BooleanSupplier keep)throws Exception{
  QuicProfiles.hash(quic);DiscordProfiles.hash(discord);
  if(!"D0".equals(discord)&&(!udp||"OFF".equals(quic)))throw new IOException("Discord/STUN требует включённого UDP/QUIC через SOCKS");
  if(!permitted())throw new IOException("Сначала активируй сетевой модуль.");
  ITransportService target=connect(true,false,false,keep,true);
  JSONObject before;
  try{before=new JSONObject(target.status());}
  catch(RemoteException unavailable){
   if(!(unavailable.getCause() instanceof ConnectException))throw unavailable;
   // The first operation only read status. No strategy request has been sent.
   AppLog.line("PACKET_CONTROL","Status connection refused before start; reconnecting once");
   target=connect(true,false,false,keep,true);
   before=new JSONObject(target.status());
  }
  String epoch=PacketRecovery.canonicalId(before.getString("service_epoch"));
  String request=null;long own;
  synchronized(STATE){
   if(cleanupPending&&!pendingInstance.equals(cancelRequestedFor)&&pendingEpoch.equals(epoch)&&pendingInstance.equals(before.optString("instance"))
    &&before.optBoolean("tap_reuse_supported")&&before.optBoolean("native_alive")&&"RUNNING".equals(before.optString("state")))
    request=pendingInstance;
  }
  if(request==null&&hasPendingCleanup()){
   try{stop();}catch(Exception e){throw new CleanupPendingException(e);}
   target=connect(true,false,false,keep,true);epoch=PacketRecovery.canonicalId(new JSONObject(target.status()).getString("service_epoch"));
  }
  synchronized(STATE){
   if(!keep.getAsBoolean())throw new CancellationException();
   if(request==null){
    if(cleanupPending)throw new IOException("Сначала подтверди остановку предыдущего пакетного запуска");
    request=UUID.randomUUID().toString();
    // Ownership must survive app death before the first start RPC.
    String descriptor=workerDescriptor;
    persist(request,epoch,descriptor);
    cleanupPending=true;pendingInstance=request;pendingEpoch=epoch;pendingWorker=descriptor;cancelRequestedFor="";stopReceipt="";
   }else if(!cleanupPending||!request.equals(pendingInstance)||!epoch.equals(pendingEpoch)){
    throw new IOException("Запуск отменён");
   }
   own=++revision;
  }
  String response=probes==null?target.startProxyUdpDiscord(profile,port,verbose,OWNER,request,udp,quic,discord):target.startYoutube(probes,OWNER,request);accept(response,own);
  JSONObject value=new JSONObject(response);drainBestEffort(target);
  if(!value.optString("service_epoch").equals(epoch)||!value.optString("instance").equals(request)||!value.optString("profile").equals(profile)||!value.optString("state").equals("RUNNING")||!value.optBoolean("native_alive"))
   throw new IOException(value.optString("error","Не запущен пакетный движок"));
  if(value.optBoolean("udp_enabled")!=udp || (udp && !quic.equals(value.optString("quic_profile"))))throw new IOException("Не подтверждены применённые UDP/QUIC-настройки");
  if(udp && !discord.equals(value.optString("discord_profile")))throw new IOException("Не подтверждён применённый Discord/STUN-профиль");
  if(probes!=null){JSONObject ports=value.getJSONObject("youtube_ports");java.util.Set<Integer> seen=new java.util.HashSet<>();
   for(int n:probes){int p=ports.optInt("T"+n);if(p<1024||p>65535||!seen.add(p))throw new IOException("Не подтверждены независимые входы YouTube");}
  }
  return value;
 }
 public static JSONObject refresh()throws Exception{return refresh(false,null);}
 static JSONObject refresh(boolean allowBackgroundLaunch,java.util.function.BooleanSupplier keep)throws Exception{
  long own=currentRevision();ITransportService target=connect(false,false,!allowBackgroundLaunch,keep);String response=target.status();
  accept(response,own);drainBestEffort(target);return new JSONObject(response);
 }
 private static void checkStopped(JSONObject result,String epoch,String expected)throws Exception{
  boolean same=result.optString("service_epoch").equals(epoch)&&result.optString("instance").equals(expected);
  JSONObject recovered=result.optJSONObject("recovery");
  boolean verified=recovered!=null&&recovered.optBoolean("verified")&&recovered.optBoolean("native_absent")
   &&recovered.optBoolean("interfaces_absent")&&recovered.optBoolean("workspace_cleanup_ok")
   &&recovered.optString("previous_instance").equals(expected)&&recovered.optString("previous_service_epoch").equals(epoch);
  if(!same&&!verified)throw new IOException(result.optString("error").isEmpty()?"Очистка прежнего запуска ещё не подтверждена; повтори Стоп после активации сетевого модуля.":result.optString("error"));
  if(result.optBoolean("native_alive"))throw new IOException("Собственный nfqws2 ещё работает");
  if(!result.optString("state").equals("STOPPED"))throw new IOException(result.optString("error","Не подтверждена остановка пакетного движка"));
  if(!result.optBoolean("workspace_cleanup_ok"))throw new IOException("Не подтверждено удаление собственной рабочей папки Lua");
  JSONObject tap=result.optJSONObject("tap");
  if(tap==null||(tap.length()>0&&!tap.optBoolean("cleanup_ok")))throw new IOException("Не все операции очистки TAP подтверждены; смотри экспорт");
 }
 public static void stop()throws Exception{
  final String expected,epoch,descriptor;final long own;
  synchronized(STATE){
   if(!cleanupPending)return;
   expected=pendingInstance;epoch=pendingEpoch;descriptor=pendingWorker;own=++revision;
  }
  ITransportService target=live();String response=independentStopReceipt(expected,epoch,descriptor);Exception failure=null;
  boolean independent=response!=null;
  boolean contactedOwner=false;
  if(response==null&&target!=null)try{
   JSONObject current=new JSONObject(target.status());
   if(epoch.equals(current.optString("service_epoch"))){
    contactedOwner=true;
    response=confirmed(target.stopProxy(expected),epoch,expected);
   }
  }catch(Exception e){failure=e;}
  if(response==null&&!contactedOwner&&!descriptor.isEmpty())try{
   WorkerChannel.Client owner=new WorkerChannel.Client(new JSONObject(descriptor));
   if(!epoch.equals(owner.epoch))throw new IOException("Сохранённый канал относится к другому модулю");
   response=confirmed(owner.call("stop",new JSONObject().put("instance",expected),15000),epoch,expected);
  }catch(Exception e){failure=e;}
  if(response==null)try{
   response=confirmed(PacketControl.stop(expected,epoch).toString(),epoch,expected);
   AppLog.line("PACKET_CONTROL","Stop acknowledged over owned local channel; instance="+expected);
  }catch(Exception e){
   if(failure==null)failure=e;
   String message="Stop path unavailable: "+e.getClass().getSimpleName()+": "+e.getMessage();
   synchronized(STATE){long now=SystemClock.elapsedRealtime();
    if(!message.equals(lastControlFailure)||now-lastControlFailureAt>=15000){AppLog.line("PACKET_CONTROL",message);lastControlFailure=message;lastControlFailureAt=now;}
   }
  }
  if(response==null){response=independentStopReceipt(expected,epoch,descriptor);independent=response!=null;}
  if(response==null){
   if(target==null)try{target=connect(false,false);}catch(Exception e){
    response=independentStopReceipt(expected,epoch,descriptor);independent=response!=null;
    if(response==null)throw new IOException("Нет связи с сетевым модулем. Запрос Стоп сохранён до подтверждения очистки.",e);
   }
  }
  if(response==null){
   JSONObject before=new JSONObject(target.status());
   if(epoch.equals(before.optString("service_epoch"))){
    if(contactedOwner&&failure!=null)throw failure;
    response=confirmed(target.stopProxy(expected),epoch,expected);
   }else{
    String result=target.recoverProxy(expected,epoch);accept(result,own);
    response=confirmed(result,epoch,expected);
    AppLog.line("PACKET_RECOVERY","Verified previous resources after helper restart; instance="+expected);
   }
  }
  // An alternate Stop can acknowledge the old helper while the active
  // connection already points to a different epoch.
  accept(response,own);
  synchronized(STATE){if(revision==own){persist("","","");cleanupPending=false;stopReceipt=response;pendingInstance="";pendingEpoch="";pendingWorker="";cancelRequestedFor="";lastControlFailure="";lastControlFailureAt=0;}}
  AppLog.line("PACKET_STOP","Confirmed instance="+expected+"; native stopped; TAP/workspace cleaned");
  if(!independent&&target!=null)drainBestEffort(target);
 }
 private static String confirmed(String response,String epoch,String expected)throws Exception{
  checkStopped(new JSONObject(response),epoch,expected);
  return response;
 }
 public static void shutdownWorker()throws Exception{
  synchronized(CONNECT){
   if(hasPendingCleanup())throw new IOException("Сначала нажми Стоп и дождись подтверждения очистки.");
   PacketWorkerTransport t=(PacketWorkerTransport)connect(false,false);JSONObject value=new JSONObject(t.status());
   if(value.optBoolean("native_alive")||!"STOPPED".equals(value.optString("state"))||!value.optBoolean("workspace_cleanup_ok"))throw new IOException("Сначала останови стратегии кнопкой Стоп.");
   t.shutdown();t.detach();worker=null;workerDescriptor="";
   if(preferences!=null&&!preferences.edit().remove("worker").remove("worker_apk").commit())throw new IOException("Не удалось удалить запись остановленного сетевого модуля.");
   EmbeddedAdb.deactivated();
  }
 }
 public static boolean hasPendingCleanup(){synchronized(STATE){return cleanupPending;}}
 public static void cancelPending(){
  final String expected;final ITransportService target=live();
  synchronized(STATE){if(!cleanupPending)return;expected=pendingInstance;cancelRequestedFor=expected;}
  if(target==null)return;
  new Thread(()->{try{target.stopProxy(expected);}catch(Exception e){AppLog.line("PACKET_CANCEL","Ожидается подтверждение остановки: "+e.getClass().getSimpleName());}},"packet-cancel").start();
 }
 public static String snapshot(){
  try{synchronized(STATE){return new JSONObject(cached).put("bridge",new JSONObject()
   .put("activation_available",permitted()).put("connected",live()!=null).put("control_transport","authenticated_loopback").put("activation_attempts",launchNumber).put("cleanup_pending",cleanupPending)
   .put("stop_confirmed",!stopReceipt.isEmpty()).put("pending_instance",pendingInstance)
   .put("pending_service_epoch",pendingEpoch)
   .put("connection_error",live()!=null?"":EmbeddedAdb.detail())).toString();}}
  catch(Exception e){return cached;}
 }
 public static JSONObject freshMetrics(){
  try{long own=currentRevision();ITransportService target=live();if(target==null)return new JSONObject();
   String response=target.snapshotMetrics();accept(response,own);drainBestEffort(target);
   JSONObject result=new JSONObject(response).optJSONObject("metrics");return result==null?new JSONObject():result;
  }catch(Exception e){return new JSONObject();}
 }
 private PacketBridge(){}
}
