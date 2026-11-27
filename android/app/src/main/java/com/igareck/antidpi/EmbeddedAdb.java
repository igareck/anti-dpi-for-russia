/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import android.content.*;
import android.os.*;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.json.*;
import io.github.muntashirakon.adb.AdbStream;

/** One-app activation: pair locally, launch the fixed worker, then close ADB. */
final class EmbeddedAdb {
 private static final Object LAUNCH=new Object(),STATE=new Object();
 private static Context context;
 private static SharedPreferences preferences;
 private static AdbDiscovery discovery;
 private static volatile long generation;
 private static volatile AdbClient active;
 private static volatile Pending pending;
 private static volatile long nextBackgroundEnable;
 private static volatile String state="Модуль не активирован",detail="",lastError="",failureStage="",workerFailure="";
 private static volatile boolean busy;
 private static volatile int manualPairPort;
 private static final class Pending {
  final String nonce=UUID.randomUUID().toString(),capability=WorkerChannel.token();
  final CountDownLatch replied=new CountDownLatch(1);volatile JSONObject descriptor;volatile boolean cancelled;volatile String remoteError="";volatile boolean external,scriptClaimed;
 }
 static void init(Context owner){synchronized(STATE){if(context!=null)return;context=owner.getApplicationContext();preferences=context.getSharedPreferences("embedded_adb",Context.MODE_PRIVATE);discovery=new AdbDiscovery(context);}}
 static boolean paired(){return preferences!=null&&preferences.getBoolean("paired",false)&&AdbIdentity.exists(context);}
 static boolean canActivate(){return android.os.Build.VERSION.SDK_INT>=30&&paired();}
 static boolean backgroundLaunchAllowed(){return context!=null&&AutoActivation.restoreWanted(context)&&!busy&&networkReady(context);}
 static boolean networkReady(Context c){
  return AutoActivation.wifiConnected(c)&&(wirelessEnabled()==1||
   (SystemClock.elapsedRealtime()>=nextBackgroundEnable&&AutoActivation.automatic(c)&&AutoActivation.permission(c)));
 }
 static boolean busy(){return busy;}
 static boolean searching(){return discovery!=null&&discovery.running();}
 static int wirelessEnabled(){return context==null?-1:WirelessDebugging.enabled(context);}
 static String status(){if(PacketBridge.hasActiveWorker())return "Сетевой модуль активен";if(state.equals("Модуль не активирован")&&paired())return "Сопряжение сохранено";return state.equals("Сетевой модуль активен")&&!PacketBridge.hasActiveWorker()?"Требуется повторная активация":state;}
 static String detail(){if(PacketBridge.hasActiveWorker())return "Сетевой модуль доступен через собственный канал. Wi-Fi-отладка для управления и повторного запуска стратегий не требуется.";if(state.equals("Модуль не активирован")&&paired())return "Включи беспроводную отладку и нажми «Активировать сетевой модуль». Повторный код не нужен.";return state.equals("Сетевой модуль активен")?"Связь с сетевым модулем потеряна. Подключитесь к Wi-Fi или используйте «Запуск через USB».":detail;}
 static void activated(){nextBackgroundEnable=0;ServiceRestoration.activated(context);failureStage="";workerFailure="";stage("Сетевой модуль активен","Управляйте стратегиями с главного экрана.");}
 static int pairPort(){return discovery==null?0:discovery.pairingPort;}
 static int connectPort(){return discovery==null?0:discovery.connectPort;}
 static void configure(int pairPort,int connectPort){manualPairPort=pairPort;if(preferences!=null)preferences.edit().putInt("connect_port",connectPort).apply();}
 static int configuredConnectPort(){return preferences==null?0:preferences.getInt("connect_port",0);}
 static void search(){
  if(discovery==null)return;generation++;lastError="";discovery.start();
  if(!busy){state="Ожидается код сопряжения";detail="Открой беспроводную отладку и выбери сопряжение по коду. Код можно ввести в уведомлении Igareck.";}
 }
 static void endSearch(){if(discovery!=null)discovery.stop();}
 private static void stage(String value,String text){state=value;detail=text;lastError="";AppLog.line("ADB_ACTIVATION",value);}
 static void failed(String operation,Throwable error){
  if(error instanceof CancellationException){state=paired()?"Сопряжение сохранено":"Модуль не активирован";detail="Активация отменена.";return;}
  if(!state.equals("Активация не завершена"))failureStage=state;lastError=error.getClass().getSimpleName();state="Активация не завершена";
  detail=(paired()?"Сопряжение сохранено. Проверь беспроводную отладку и порт подключения, затем повтори активацию.":"Проверь свежий код и порт сопряжения. Окно Android с кодом должно оставаться открытым.");
  if(failureStage.equals("Подключение к ADB"))detail=WirelessDebugging.unavailable(wirelessEnabled());
  if(error instanceof UserMessage)detail=error.getMessage();
  AppLog.line("ADB_ACTIVATION_ERROR",operation+"; "+lastError+"; stage="+failureStage);
 }
 private static class UserMessage extends IOException {UserMessage(String message){super(message);}}
 static final class ActivationRequiredException extends UserMessage {
  ActivationRequiredException(){super("Сопряжение сохранено. Для запуска подключись к Wi-Fi или используй «Запуск через USB» с компьютера.");}
 }
 private static void wanted(long own)throws IOException{if(own!=generation)throw new CancellationException("Activation cancelled");}
 private static void wanted(long own,java.util.function.BooleanSupplier keep)throws IOException{wanted(own);if(!keep.getAsBoolean())throw new CancellationException();}
 private static boolean restartWireless(long own,java.util.function.BooleanSupplier keep)throws Exception{
  wanted(own,keep);
  if(PacketBridge.hasActiveWorker()||wirelessEnabled()!=1||!AutoActivation.automatic(context)||!AutoActivation.permission(context)||!AutoActivation.wifiConnected(context))return false;
  stage("Восстановление отладки","Доступный порт ADB ещё не найден. Выполняется один повторный запуск беспроводной отладки.");
  discovery.stop();
  if(!AutoActivation.disableDebugging(context)){discovery.start(true);return false;}
  // Let Android process DISABLE before ENABLE. Merely rewriting 1 does not
  // reset AdbDebuggingManager's internal enabled state.
  long until=SystemClock.elapsedRealtime()+1500;
  while(SystemClock.elapsedRealtime()<until){wanted(own,keep);Thread.sleep(100);}
  wanted(own,keep);
  if(PacketBridge.hasActiveWorker()||!AutoActivation.automatic(context)||!AutoActivation.wifiConnected(context)){discovery.start(true);return false;}
  boolean enabled=AutoActivation.requestDebugging(context);discovery.start(true);
  AppLog.line("ADB_RECOVERY","Wireless debugging cycle completed; settings_write="+enabled+"; awaiting authenticated TLS endpoint");
  return enabled;
 }
 static void pair(int suppliedPort,String suppliedCode)throws Exception{
  String code=AdbBootstrap.pairingCode(suppliedCode);long own=generation;int port=suppliedPort!=0?suppliedPort:manualPairPort;
  if(port==0)port=pairPort();if(port==0)throw new UserMessage("Порт сопряжения ещё не найден. Открой окно «Сопряжение с помощью кода» или введи его порт вручную.");
  synchronized(LAUNCH){wanted(own);busy=true;stage("Выполняется сопряжение","Не закрывай окно Android с кодом сопряжения.");
   try(AdbClient client=new AdbClient(context,true)){active=client;if(!client.pair("127.0.0.1",port,code))throw new IOException("Pairing rejected");wanted(own);
    if(!preferences.edit().putBoolean("paired",true).commit())throw new IOException("Cannot save pairing state");
    stage("Сопряжение сохранено","Можно активировать сетевой модуль. Код повторно вводить не требуется.");
   }finally{active=null;busy=false;code="";}
  }
 }
 static String launchWorker()throws Exception{
  return launchWorker(true,()->true);
 }
 static String launchWorker(boolean userRequested)throws Exception{return launchWorker(userRequested,()->true);}
 static String launchWorker(boolean userRequested,java.util.function.BooleanSupplier keep)throws Exception{
  synchronized(LAUNCH){
   if(context==null||!paired())throw new UserMessage("Открой «Активация сетевого модуля» и выполни сопряжение ADB.");
   if(!keep.getAsBoolean())throw new CancellationException();
   if(!AutoActivation.wifiConnected(context))throw new ActivationRequiredException();
   final long own=generation;busy=true;workerFailure="";
   // Keep the subscription alive across pairing completion and activation.
   discovery.start();
   stage("Подключение к ADB","Определяется порт подключения этого устройства.");
   Pending request=new Pending();pending=request;AdbStream stream=null;AdbClient client=null;
   try{
    if(wirelessEnabled()==0&&AutoActivation.automatic(context)&&AutoActivation.permission(context)
        &&(userRequested||SystemClock.elapsedRealtime()>=nextBackgroundEnable)){
     stage("Включается отладка","Android может запросить разрешение для текущей Wi-Fi-сети.");
     nextBackgroundEnable=SystemClock.elapsedRealtime()+60000;
     AutoActivation.requestDebugging(context);long enabledUntil=SystemClock.elapsedRealtime()+4000;
     while(wirelessEnabled()==0&&SystemClock.elapsedRealtime()<enabledUntil){wanted(own,keep);Thread.sleep(100);}
    }
    if(wirelessEnabled()==0)throw new UserMessage(WirelessDebugging.unavailable(0));
    long began=SystemClock.elapsedRealtime(),candidateDeadline=began+45000,preferFreshUntil=began+8000;
    long noCandidateSince=began,nextPropertyRead=0;
    boolean connected=false,refreshed=false,cycleChecked=false,cycled=false;
    int attempts=0,systemPort=0;
    java.util.HashSet<Integer> tried=new java.util.HashSet<>();
    while(attempts<8&&SystemClock.elapsedRealtime()<candidateDeadline){
     wanted(own,keep);
     if(!AutoActivation.wifiConnected(context))
      throw new UserMessage("Подключение к Wi-Fi потеряно. Сопряжение сохранено.");
     long now=SystemClock.elapsedRealtime();
     if(now>=nextPropertyRead){systemPort=AdbPorts.systemPort();nextPropertyRead=now+1000;}
     int port=0,manual=configuredConnectPort(),resolved=connectPort();
     int cached=now>=preferFreshUntil||manual!=0||resolved!=0||systemPort!=0?preferences.getInt("last_connect_port",0):0;
     for(int candidate:AdbPorts.candidates(manual,resolved,systemPort,cached))if(!tried.contains(candidate)){port=candidate;break;}
     if(port==0){
      if(!refreshed&&now>=preferFreshUntil&&now-noCandidateSince>=1500){
       refreshed=true;noCandidateSince=now;
       AppLog.line("ADB_DISCOVERY","Refreshing connection discovery without a network filter");
       discovery.refresh();continue;
      }
      if(userRequested&&refreshed&&!cycleChecked&&now-noCandidateSince>=5000){
       cycleChecked=true;cycled=restartWireless(own,keep);
       if(cycled){
        preferences.edit().putInt("last_connect_port",0).apply();tried.clear();
        noCandidateSince=SystemClock.elapsedRealtime();continue;
       }
      }
      Thread.sleep(100);continue;
     }
     tried.add(port);attempts++;noCandidateSince=SystemClock.elapsedRealtime();
     wanted(own,keep);client=new AdbClient(context,false);client.setTimeout(3,TimeUnit.SECONDS);active=client;
     String source=port==manual?"manual":port==resolved?"discovery":port==systemPort?"system_property":"last_success";
     AppLog.line("ADB_CONNECT","Trying local TLS port="+port+" source="+source);
     try{if(!client.connectLocal(port))throw new IOException("ADB connect not acknowledged");wanted(own);if(!keep.getAsBoolean())throw new CancellationException();preferences.edit().putInt("last_connect_port",port).apply();connected=true;AppLog.line("ADB_CONNECT","Verified local TLS port="+port+" source="+source);break;}
     catch(Exception e){try{client.close();}catch(Exception ignored){}active=null;client=null;wanted(own);if(!keep.getAsBoolean())throw new CancellationException();AppLog.line("ADB_CONNECT_RETRY","Local candidate failed: "+e.getClass().getSimpleName()+" port="+port+" source="+source);}
    }
    if(!connected){
     AppLog.line("ADB_ENDPOINT_TIMEOUT","wireless_setting="+wirelessEnabled()+"; discovered_port="+connectPort()+"; system_port="+systemPort+"; attempts="+attempts+"; discovery_refresh="+refreshed+"; wireless_cycle="+cycled);
     throw new UserMessage("Доступный TLS-порт ADB не обнаружен. Сопряжение сохранено. Если на главном экране «Отладка по Wi-Fi» видна строка «IP-адрес и порт», введи этот порт вручную. Иначе проверь разрешение для сети, перезапусти отладку или используй USB.");
    }
    stage("Запускается сетевой модуль","ADB подключён. Запускается сетевой модуль Igareck AntiDPI.");
    stream=client.openStream("exec:sh");final InputStream output=stream.openInputStream();
    Thread drain=new Thread(()->{try{byte[] b=new byte[2048];StringBuilder line=new StringBuilder();boolean overflow=false;int count;while((count=output.read(b))>=0){for(int i=0;i<count;i++){int c=b[i]&255;if(c==10){String marker=line.toString();if(!overflow&&(WorkerFailure.valid(marker)||marker.equals("IGARECK_ADB_UID_ERROR"))){if(request.remoteError.isEmpty())request.remoteError=marker;request.replied.countDown();AppLog.line("ADB_BOOTSTRAP",marker);}line.setLength(0);overflow=false;}else if(line.length()<160)line.append((char)c);else overflow=true;}}}catch(IOException ignored){}},"adb-bootstrap-output");drain.setDaemon(true);drain.start();
    android.content.pm.ApplicationInfo app=AppIdentity.resolve(context);
    String script=AdbBootstrap.script(app.sourceDir,app.nativeLibraryDir,app.uid,request.capability,request.nonce,android.os.Process.is64Bit());
    OutputStream input=stream.openOutputStream();input.write(script.getBytes("UTF-8"));input.flush();
    long until=SystemClock.elapsedRealtime()+25000;
    while(!request.replied.await(100,TimeUnit.MILLISECONDS)){wanted(own);if(!keep.getAsBoolean())throw new CancellationException();if(SystemClock.elapsedRealtime()>until)throw new UserMessage("Сетевой модуль не подтвердил запуск. Проверь беспроводную отладку и экспортируй журнал для диагностики.");}
    wanted(own);if(!keep.getAsBoolean())throw new CancellationException();if(!request.remoteError.isEmpty()){workerFailure=request.remoteError;throw new UserMessage(WorkerFailure.description(request.remoteError));}if(request.cancelled||request.descriptor==null)throw new CancellationException("Activation cancelled");
    // The OS enforces DUMP on the explicit bootstrap receiver; the receipt is MAC-bound.
    stage("Подтверждение сетевого модуля","Проверяется защищённый канал управления.");
    return request.descriptor.toString();
   }finally{
    endSearch();pending=null;active=null;busy=false;if(stream!=null)try{stream.close();}catch(Exception ignored){}if(client!=null)try{client.close();}catch(Exception ignored){}
   }
  }
 }
 static String launchUsbWorker()throws Exception{
  synchronized(LAUNCH){
   if(context==null)throw new IOException("Application is not initialized");
   long own=generation;workerFailure="";Pending request=new Pending();request.external=true;pending=request;busy=true;
   stage("Ожидается USB-команда","Подключи устройство к компьютеру, разреши USB-отладку и выполни скопированную команду ADB. Ожидание до трёх минут.");
   try{long until=SystemClock.elapsedRealtime()+180000;
    while(!request.replied.await(100,TimeUnit.MILLISECONDS)){wanted(own);if(SystemClock.elapsedRealtime()>until)throw new UserMessage("Время USB-запуска истекло. Нажми «Запуск через USB» и повтори команду.");}
    wanted(own);if(request.cancelled||request.descriptor==null)throw new CancellationException();
    return request.descriptor.toString();
   }finally{if(pending==request)pending=null;busy=false;}
  }
 }
 static String usbScript()throws Exception{
  Pending p=pending;if(p==null||!p.external||p.cancelled)throw new IOException("No pending USB activation");
  synchronized(p){if(pending!=p||p.scriptClaimed)throw new IOException("USB script already issued");p.scriptClaimed=true;
   android.content.pm.ApplicationInfo app=AppIdentity.resolve(context);
   return AdbBootstrap.script(app.sourceDir,app.nativeLibraryDir,app.uid,p.capability,p.nonce,android.os.Process.is64Bit());
  }
 }
 static void deactivated(){state=paired()?"Сопряжение сохранено":"Модуль не активирован";detail="Сетевой модуль остановлен пользователем. Для нового запуска нажми «Активировать сетевой модуль».";}
 static void bootstrapReply(String nonce,String descriptor,String auth){
  Pending p=pending;if(p==null||p.cancelled)return;
  try{JSONObject value=AdbBootstrap.verify(p.capability,p.nonce,nonce,descriptor,auth);if(pending==p&&!p.cancelled){p.descriptor=value;p.replied.countDown();}}
  catch(Exception ignored){AppLog.line("ADB_BOOTSTRAP","Rejected stale or unauthenticated bootstrap receipt");}
 }
 static void cancel(){generation++;Pending p=pending;if(p!=null){p.cancelled=true;p.replied.countDown();}AdbClient c=active;if(c!=null)new Thread(()->{try{c.close();}catch(Exception ignored){}},"adb-cancel").start();endSearch();if(PacketBridge.hasActiveWorker()){state="Сетевой модуль активен";detail="Отменены только поиск и активация. Активный сетевой модуль сохранён; для остановки движка нажми Стоп.";}else{state=paired()?"Сопряжение сохранено":"Модуль не активирован";detail="Активация отменена.";}}
 static String snapshot(){try{return new JSONObject().put("paired",paired()).put("state",PacketBridge.hasActiveWorker()?"Сетевой модуль активен":status()).put("activation_operation_state",status()).put("detail",detail()).put("worker_active",PacketBridge.hasActiveWorker()).put("wireless_debugging_enabled",wirelessEnabled()).put("activation_options",new JSONObject(AutoActivation.snapshot(context))).put("service_restoration",new JSONObject(ServiceRestoration.snapshot(context))).put("discovery",new JSONObject(discovery==null?"{}":discovery.snapshot())).put("busy",busy).put("error_type",lastError).put("failure_stage",failureStage).put("worker_failure",workerFailure).put("pair_port_discovered",pairPort()!=0).put("connect_port_discovered",connectPort()!=0).put("local_device_only",true).put("external_shizuku_required",false).toString();}catch(Exception e){return "{}";}}
 private EmbeddedAdb(){}
}
