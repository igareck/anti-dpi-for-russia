/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import org.json.*;

/** Shell UID 2000 owns the TAP and exactly one native child. Never a VpnService. */
public final class NativePacketService extends ITransportService.Stub {
 private final Context context;
 private final ApplicationInfo application;
 private final String workerCapability;
 private final String serviceEpoch=UUID.randomUUID().toString();
 private final Object operation=new Object(),logsLock=new Object(),stateLock=new Object();
 private final ArrayDeque<String> logs=new ArrayDeque<>(),evidenceLogs=new ArrayDeque<>();
 private volatile String state="STOPPED",error="",profile="",instance="",lastMetrics="{}",lastTap="{}";
 private volatile int port;
 private volatile String youtubePorts="{}";
 private volatile boolean udpEnabled;
 private volatile String quicProfile="Q1",quicHash="",discordProfile="D0",discordHash="";
 private volatile String recipeHash="";
 private volatile int recipeNumber;
 private volatile boolean cancelled,ownerGone;
 private volatile int ownerLeaseExpirations;
 private volatile java.lang.Process child;
 private volatile TapSession tap;
 private File work;
 private PacketRecovery recovery;
 private OutputStream verifierLease;
 private volatile PacketControl.Server control;
 private volatile String lastRecovery="{}";
 private volatile boolean workspaceCleanupOk=true;
 private volatile IBinder ownerToken;
 private IBinder.DeathRecipient death;
 private volatile CountDownLatch ready;
 private volatile CountDownLatch snapshot;
 private volatile Thread output;
 private volatile long lastOperation=SystemClock.elapsedRealtime();
 public NativePacketService(Context context){
  this(context,false);
 }
 NativePacketService(Context context,boolean retainWhileAppAlive){
  this(context,retainWhileAppAlive,null);
 }
 NativePacketService(Context context,boolean retainWhileAppAlive,String workerCapability){
  this.context=context;
  this.workerCapability=workerCapability;
  application=AppIdentity.resolve(context);
  Thread idle=new Thread(()->{
   for(;;){try{Thread.sleep(5000);}catch(InterruptedException e){return;}
    if(child==null&&tap==null&&work==null&&SystemClock.elapsedRealtime()-lastOperation>60000){synchronized(operation){
     if(child==null&&tap==null&&work==null&&!state.equals("STARTING")&&SystemClock.elapsedRealtime()-lastOperation>60000)System.exit(0);
    }}
   }
  },"packet-idle-exit");idle.setDaemon(true);if(!retainWhileAppAlive)idle.start();
 }
 private void enforce(){int uid=Binder.getCallingUid();if(uid!=application.uid&&uid!=2000)throw new SecurityException("Caller does not own this app: caller_uid="+uid+"; app_uid="+application.uid+"; app="+AppIdentity.PACKAGE_NAME);}
 @Override public int getUid(){enforce();return Process.myUid();}
 private void log(String value){synchronized(logsLock){
  boolean evidence=value.startsWith("[IGARECK_UDP_")||value.startsWith("[IGARECK_DISCORD_")||value.startsWith("[IGARECK_NFQWS2_METRICS]");
  ArrayDeque<String> target=evidence?evidenceLogs:logs;target.addLast(value.length()>4096?value.substring(0,4096):value);
  while(target.size()>(evidence?512:256))target.removeFirst();
 }}
 private void check()throws IOException{if(cancelled||ownerGone)throw new IOException("Запуск отменён");}
 @Override public String startProxy(String id,int chosenPort,boolean verbose,IBinder owner,String requestId){
  return startProxyUdp(id,chosenPort,verbose,owner,requestId,false,"Q1");
 }
 @Override public String startProxyUdp(String id,int chosenPort,boolean verbose,IBinder owner,String requestId,boolean udp,String quic){
  return startProxyUdpDiscord(id,chosenPort,verbose,owner,requestId,udp,quic,"D0");
 }
 @Override public String startProxyUdpDiscord(String id,int chosenPort,boolean verbose,IBinder owner,String requestId,boolean udp,String quic,String discord){
  return startOwned(TcpIds.canonical(id),chosenPort,verbose,owner,requestId,udp,quic,discord,null);
 }
 @Override public String startYoutube(int[] numbers,IBinder owner,String requestId){
  enforce();
  if(numbers==null||numbers.length<1||numbers.length>5)throw new IllegalArgumentException("Parallel count must be 1–5");
  Set<Integer> seen=new HashSet<>();for(int n:numbers)if(n<1||n>484||!seen.add(n))throw new IllegalArgumentException("Unknown/duplicate TCP strategy");
  return startOwned("T"+numbers[0],0,false,owner,requestId,false,"Q1","D0",numbers.clone());
 }
 private String startOwned(String id,int chosenPort,boolean verbose,IBinder owner,String requestId,boolean udp,String quic,String discord,int[] probes){
  enforce();
  try{QuicProfiles.hash(quic);DiscordProfiles.hash(discord);}catch(IOException e){throw new IllegalArgumentException(e.getMessage());}
  if(!"D0".equals(discord)&&(!udp||"OFF".equals(quic)))throw new IllegalArgumentException("Discord/STUN requires UDP/QUIC through SOCKS");
  if(id==null||!id.matches("T[1-9][0-9]{0,2}"))throw new IllegalArgumentException("Unknown packet profile");
  if((probes==null&&(chosenPort<1024||chosenPort>65535))||owner==null)throw new IllegalArgumentException("Invalid proxy ownership or port");
  if(requestId==null||!UUID.fromString(requestId).toString().equals(requestId))throw new IllegalArgumentException("Invalid request instance");
  synchronized(operation){
   long identity=Binder.clearCallingIdentity();
   try{
    if(Process.myUid()!=2000)throw new IOException("Нужна активированный сетевой модуль с UID 2000.");
    final boolean sameRun;
    final boolean reuseTap;
    synchronized(stateLock){
     sameRun=requestId.equals(instance);
     if(sameRun){
      check();
      if(ownerToken!=owner||!"RUNNING".equals(state))throw new IOException("Прежний запуск больше не доступен для смены стратегии");
     }else{instance=requestId;cancelled=false;}
     reuseTap=sameRun&&child!=null&&child.isAlive()&&tap!=null&&tap.reusable()&&work!=null;
     state="STARTING";
    }
    if(reuseTap)stopNativeChild();
    else{
     stopOwned(!sameRun);
     if(child!=null||tap!=null||work!=null||!workspaceCleanupOk||tapCleanupFailed())
      throw new IOException("Не подтверждена очистка предыдущего TAP; смотри экспорт.");
     if(recovery==null)recovery=new PacketRecovery(context,application.uid,serviceEpoch);
     lastRecovery=recovery.sweep(null,null).toString();
    }
    check();lastOperation=SystemClock.elapsedRealtime();
    state="STARTING";error="";profile=probes==null?id:"YOUTUBE";port=chosenPort;lastMetrics="{}";youtubePorts="{}";
    udpEnabled=udp;quicProfile=quic;quicHash=udp?QuicProfiles.hash(quic):"";discordProfile=discord;discordHash=udp?DiscordProfiles.hash(discord):"";
    Catalogue catalogue=Catalogue.fromApk(application.sourceDir);Catalogue.Recipe recipe=catalogue.get(id);
    recipeHash=recipe==null?"":recipe.hash;recipeNumber=recipe==null?0:recipe.number;
    if(!reuseTap){
     ownerToken=owner;death=()->ownerLost(requestId,owner);ownerToken.linkToDeath(death,0);
     check();work=recovery.begin(instance);workspaceCleanupOk=false;
     final File ownedWork=work;
     if(verifierLease==null&&workerCapability!=null)try{
      verifierLease=StopVerifier.launch(application,instance,serviceEpoch,workerCapability,message->log("[IGARECK_STOP_VERIFIER] "+message));
     }catch(Exception e){log("[IGARECK_STOP_VERIFIER] Launch unavailable: "+e.getClass().getSimpleName());}
     // This is an additional Stop path. A firmware restriction on abstract
     // sockets must not prevent the established Binder/TAP transport starting.
     try{control=new PacketControl.Server(application.uid,instance,serviceEpoch,this::stopProxy,message->log("[IGARECK_STOP_CONTROL] "+message));}
     catch(Exception e){control=null;log("[IGARECK_STOP_CONTROL] Additional channel unavailable: "+e.getClass().getSimpleName()+"; retained Binder remains the primary Stop path");}
     tap=new TapSession(context,(name,visibility)->recovery.checkpoint(ownedWork,name,visibility));
     check();tap.open();lastTap=tap.progress();check();
     try(ZipFile apk=new ZipFile(application.sourceDir)){
      for(String name:new String[]{"zapret-lib.lua","zapret-antidpi.lua","igareck-actual-tls.lua"}){
       File dest=new File(work,name);
       try(InputStream in=apk.getInputStream(apk.getEntry("assets/nfqws2/"+name));OutputStream out=new FileOutputStream(dest)){
        byte[] bytes=Catalogue.bytes(in);
        if(name.equals("igareck-actual-tls.lua")&&!Catalogue.GUARD_SHA256.equals(Catalogue.digest(bytes)))throw new IOException("ActualTLS guard SHA-256 mismatch");
        out.write(bytes);
       }Os.chmod(dest.getAbsolutePath(),0600);
      }
     }
    }
    log("[IGARECK_TAP_MODE] "+(reuseTap?"reused":"cold")+"; instance="+instance);
    File executable=new File(application.nativeLibraryDir,"libnfqws2.so");
    if(!executable.canExecute())throw new IOException("В APK отсутствует nfqws2 для текущего ABI.");
    String endpoint="igareck-tap-"+instance;
    try(LocalServerSocket server=new LocalServerSocket(endpoint)){
     List<String> command=probes==null?PacketCommand.build(catalogue,executable.getAbsolutePath(),work,endpoint,id,instance,chosenPort,verbose,udp,quic,discord):PacketCommand.youtube(catalogue,executable.getAbsolutePath(),work,endpoint,instance,probes);
     log("[IGARECK_RECIPE] number="+recipeNumber+" sha256="+recipeHash+" catalogue_sha256="+Catalogue.SHA256+" ports=1-65535 udp="+udp+" quic="+quic+" quic_sha256="+quicHash+" discord="+discord+" discord_sha256="+discordHash);
     ProcessBuilder builder=new ProcessBuilder(command).directory(work).redirectErrorStream(true);
     for(String key:new String[]{"LUA_PATH","LUA_CPATH","LUA_INIT","LUA_INIT_5_4"})builder.environment().remove(key);
     ready=new CountDownLatch(1);child=builder.start();final java.lang.Process owned=child;
     output=new Thread(()->readOutput(owned),"nfqws2-output");output.setDaemon(true);output.start();
     long until=SystemClock.elapsedRealtime()+8000;StructPollfd poll=new StructPollfd();poll.fd=server.getFileDescriptor();poll.events=(short)OsConstants.POLLIN;
     while(Os.poll(new StructPollfd[]{poll},100)==0){check();if(!owned.isAlive())throw new IOException("nfqws2 завершился до подключения TAP");if(SystemClock.elapsedRealtime()>until)throw new IOException("nfqws2 не запросил TAP FD");}
     check();
     try(LocalSocket peer=server.accept()){
      if(peer.getPeerCredentials().getUid()!=Process.myUid())throw new SecurityException("Unexpected FD recipient UID");
      peer.setFileDescriptorsForSend(new FileDescriptor[]{tap.descriptor()});
      peer.getOutputStream().write(tap.configuration());peer.getOutputStream().flush();peer.setFileDescriptorsForSend(null);
     }
     until=SystemClock.elapsedRealtime()+8000;
     while(!ready.await(100,TimeUnit.MILLISECONDS)){check();if(!owned.isAlive())throw new IOException("nfqws2 завершился при инициализации");if(SystemClock.elapsedRealtime()>until)throw new IOException("nfqws2 не подтвердил SOCKS-вход");}
     check();if(!owned.isAlive())throw new IOException("nfqws2 завершился после старта");state="RUNNING";
     log("[IGARECK_TRANSPORT_READY] profile="+profile+" instance="+instance+" uid="+Process.myUid()+" port="+port);
    }
   }catch(Throwable e){error=e.getClass().getSimpleName()+": "+e.getMessage();log("[IGARECK_PACKET_ERROR] "+error);stopOwned();if(!cancelled)state="ERROR";}
   finally{Binder.restoreCallingIdentity(identity);}
   return statusOwned();
  }
 }
 void ownerLost(String expected,IBinder expectedOwner){
  final TapSession cancelTap;
  synchronized(stateLock){
   if(!instance.equals(expected)||ownerToken!=expectedOwner)return;
   cancelled=true;cancelTap=tap;
  }
  cancelOwnedTap(cancelTap);
  new Thread(()->{synchronized(operation){
   if(!instance.equals(expected)||ownerToken!=expectedOwner)return;
   stopOwned();if(child==null&&tap==null&&work==null)System.exit(0);
  }},"packet-owner-death").start();
 }
 private static void cancelOwnedTap(TapSession owned) {
  if(owned==null)return;
  // The service has authenticated the caller/owner before reaching this point.
  // Nested TAP operations execute as this shell helper, not the remote app UID.
  long identity=Binder.clearCallingIdentity();
  try{owned.cancel();}finally{Binder.restoreCallingIdentity(identity);}
 }
 private void readOutput(java.lang.Process owned){
  try(BufferedReader in=new BufferedReader(new InputStreamReader(owned.getInputStream(),StandardCharsets.UTF_8))){
   String line;while((line=in.readLine())!=null){log(line);
    if(child==owned&&line.startsWith("[IGARECK_PROBE_PORT]")&&line.endsWith("instance="+instance)){
     java.util.regex.Matcher m=java.util.regex.Pattern.compile("profile=(T[0-9]+) port=([0-9]+) ").matcher(line);
     if(m.find())synchronized(stateLock){try{youtubePorts=new JSONObject(youtubePorts).put(m.group(1),Integer.parseInt(m.group(2))).toString();}catch(Exception e){log("[IGARECK_PROBE_PORT_ERROR]");}}
    }
    if(child==owned&&line.startsWith("[IGARECK_LISTEN_READY]")&&line.contains("instance="+instance)&&line.contains("udp_associate="+(udpEnabled?"true":"false"))&&(!udpEnabled||(line.contains("quic="+quicProfile+" ")&&line.contains("discord="+discordProfile+" ")))){CountDownLatch r=ready;if(r!=null)r.countDown();}
    String marker="[IGARECK_NFQWS2_METRICS] ";if(child==owned&&line.startsWith(marker)){lastMetrics=line.substring(marker.length());if(line.contains("\"event\":\"SNAPSHOT\"")){CountDownLatch waiter=snapshot;if(waiter!=null)waiter.countDown();}}
   }
  }catch(IOException e){log("[IGARECK_PACKET_READER] "+e.getClass().getSimpleName());}
  if(child==owned&&state.equals("RUNNING")){
   new Thread(()->{synchronized(operation){if(child==owned&&state.equals("RUNNING")){error="nfqws2 завершился, код "+owned.exitValue();stopOwned();state="ERROR";}}},"packet-exit-cleanup").start();
  }
 }
 private void stopNativeChild()throws IOException,InterruptedException{
  java.lang.Process owned=child;
  if(owned==null)return;
  if(owned.isAlive()){
   owned.destroy();
   if(!owned.waitFor(2000,TimeUnit.MILLISECONDS)){
    owned.destroyForcibly();
    if(!owned.waitFor(2000,TimeUnit.MILLISECONDS))throw new IOException("Native child did not exit");
   }
  }
  child=null;
  Thread reader=output;output=null;
  if(reader!=null&&reader!=Thread.currentThread())reader.join(500);
 }
 private void stopOwned(){stopOwned(true);}
 private void stopOwned(boolean releaseVerifier){
  try{stopNativeChild();}
  catch(Exception e){
   if(e instanceof InterruptedException)Thread.currentThread().interrupt();
   error="Ошибка остановки собственного движка: "+e.getMessage();log(error);state="ERROR";return;
  }
  TapSession ownTap=tap;
  if(ownTap!=null){
   ownTap.close();lastTap=ownTap.progress();log("[IGARECK_TAP_CLEANUP] "+lastTap);
   if(tapCleanupFailed()){workspaceCleanupOk=false;error="Очистка TAP не завершена; повтори Стоп.";state="ERROR";return;}
   tap=null;
  }
  if(work!=null){
   if(tapCleanupFailed()){
    workspaceCleanupOk=false;error="Не подтверждена очистка TAP; журнал ресурсов сохранён для восстановления.";state="ERROR";return;
   }
   if(recovery!=null){
    try{recovery.finish(work);workspaceCleanupOk=true;work=null;}
    catch(Exception e){workspaceCleanupOk=false;error="Не удалось удалить собственную рабочую папку Lua: "+e.getMessage();state="ERROR";log(error);return;}
   }else{
   boolean okay=true;File[] files=work.listFiles();if(files==null&&work.exists())okay=false;
   if(files!=null)for(File f:files)if(!f.delete()&&f.exists())okay=false;
   if(!work.delete()&&work.exists())okay=false;workspaceCleanupOk=okay;
   if(okay)work=null;else{error="Не удалось удалить собственную рабочую папку Lua; повтори Стоп.";state="ERROR";log(error);return;}
   }
  }
  if(ownerToken!=null&&death!=null){try{ownerToken.unlinkToDeath(death,0);}catch(Exception ignored){}}ownerToken=null;death=null;
  PacketControl.Server stopControl=control;if(stopControl!=null){stopControl.close();control=null;}
  try{if(recovery!=null)recovery.close();}
  catch(IOException e){workspaceCleanupOk=false;error="Не удалось освободить блокировку собственных ресурсов: "+e.getMessage();state="ERROR";log(error);return;}
  workspaceCleanupOk=true;state="STOPPED";lastOperation=SystemClock.elapsedRealtime();
  // During a cold Apply of the same instance the verifier must still wait.
  if(releaseVerifier&&verifierLease!=null){
   try{verifierLease.close();}catch(IOException e){log("[IGARECK_STOP_VERIFIER] Lease close: "+e.getClass().getSimpleName());}
   finally{verifierLease=null;}
  }
 }
 private boolean tapCleanupFailed(){try{JSONObject o=new JSONObject(lastTap);return o.has("cleanup_ok")&&!o.optBoolean("cleanup_ok");}catch(Exception e){return true;}}
 @Override public String recoverProxy(String expectedInstance,String expectedEpoch){
  enforce();synchronized(operation){long identity=Binder.clearCallingIdentity();try{
   PacketRecovery.canonicalId(expectedInstance);PacketRecovery.canonicalId(expectedEpoch);
   if(child!=null||tap!=null||work!=null||state.equals("STARTING")||state.equals("RUNNING"))
    throw new IOException("Текущий модуль владеет активным запуском; восстановление чужого экземпляра отклонено.");
   if(Process.myUid()!=2000)throw new IOException("Для восстановления нужна активированный сетевой модуль, UID 2000.");
   if(recovery==null)recovery=new PacketRecovery(context,application.uid,serviceEpoch);
   try{lastRecovery=recovery.sweep(expectedInstance,expectedEpoch).toString();}
   finally{recovery.close();}
   state="STOPPED";error="";workspaceCleanupOk=true;lastTap="{\"cleanup_ok\":true,\"recovered\":true}";
   lastOperation=SystemClock.elapsedRealtime();log("[IGARECK_PACKET_RECOVERY] "+lastRecovery);
  }catch(Exception e){error="Восстановление ресурсов: "+e.getMessage();lastRecovery="{}";log("[IGARECK_PACKET_RECOVERY_ERROR] "+error);}
  finally{Binder.restoreCallingIdentity(identity);}return statusOwned();}
 }
 @Override public String stopProxy(String expectedInstance){
  enforce();final TapSession cancelTap;
  synchronized(stateLock){
   boolean matches=instance.equals(expectedInstance);if(matches)cancelled=true;
   // Capture the owned session with its instance. A new Apply may replace tap
   // before this cancellation thread reaches cancel().
   cancelTap=matches?tap:null;
  }
  cancelOwnedTap(cancelTap);
  synchronized(operation){long identity=Binder.clearCallingIdentity();try{
   // A delayed cancellation from an older Apply must never stop a newer run.
   if(instance.equals(expectedInstance)){stopOwned();if(state.equals("STOPPED"))error="";}
   return statusOwned();
  }finally{Binder.restoreCallingIdentity(identity);}}
 }
 private String statusOwned(){
  synchronized(stateLock){try{return new JSONObject().put("state",state).put("error",error).put("profile",profile).put("port",port).put("instance",instance)
    .put("youtube_ports",new JSONObject(youtubePorts)).put("recipe_number",recipeNumber).put("recipe_sha256",recipeHash).put("catalogue_sha256",Catalogue.SHA256).put("tcp_ports","1-65535")
    .put("udp_enabled",udpEnabled).put("quic_profile",quicProfile).put("quic_recipe_sha256",quicHash).put("discord_profile",discordProfile).put("discord_recipe_sha256",discordHash).put("udp_ports","1-65535")
    .put("application_id",AppIdentity.PACKAGE_NAME).put("app_uid",application.uid).put("uid",Process.myUid()).put("service_epoch",serviceEpoch).put("service_pid",Process.myPid()).put("owner_lease_expirations",ownerLeaseExpirations).put("native_alive",child!=null&&child.isAlive()).put("tap_reuse_supported",true).put("workspace_cleanup_ok",workspaceCleanupOk).put("metrics",new JSONObject(lastMetrics)).put("tap",new JSONObject(lastTap)).put("recovery",new JSONObject(lastRecovery)).put("no_vpn_service",true).toString();}
   catch(Exception e){return "{\"state\":\"ERROR\",\"error\":\"Cannot serialize transport state\"}";}}
 }
 @Override public String status(){enforce();return statusOwned();}
 @Override public String snapshotMetrics(){
  enforce();synchronized(operation){java.lang.Process owned=child;if(owned!=null&&owned.isAlive()){
   snapshot=new CountDownLatch(1);
   try{owned.getOutputStream().write("snapshot\n".getBytes(StandardCharsets.US_ASCII));owned.getOutputStream().flush();
    if(!snapshot.await(700,TimeUnit.MILLISECONDS))log("[IGARECK_SNAPSHOT_TIMEOUT]");
   }catch(Exception e){log("[IGARECK_SNAPSHOT_UNAVAILABLE] "+e.getClass().getSimpleName());}finally{snapshot=null;}
  }return statusOwned();}
 }
 private static int drainChunk(ArrayDeque<String> queue,JSONArray into,int budget){
  int used=0;while(!queue.isEmpty()&&queue.peekFirst().length()<=budget-used){String line=queue.removeFirst();used+=line.length();into.put(line);}return used;
 }
 @Override public String drainLogs(){enforce();JSONArray a=new JSONArray();synchronized(logsLock){
  // Even twice JSON-escaped control characters fit the 1 MiB RPC frame.
  // Keep the remainder queued and give both ordinary/evidence logs a share.
  int budget=131072;budget-=drainChunk(logs,a,budget/2);budget-=drainChunk(evidenceLogs,a,budget);drainChunk(logs,a,budget);
 }return a.toString();}
 void releaseOwner(){
  enforce();cancelled=true;TapSession t=tap;cancelOwnedTap(t);
  synchronized(operation){ownerLeaseExpirations++;stopOwned();log("[IGARECK_OWNER_IDLE] App heartbeat expired; cleanup="+state+"; authenticated activation retained");}
 }
 @Override public void destroy(){enforce();ownerGone=true;cancelled=true;TapSession t=tap;cancelOwnedTap(t);new Thread(()->{synchronized(operation){stopOwned();if(child==null&&tap==null&&work==null)System.exit(0);}},"packet-service-destroy").start();}
}
