package com.igareck.antidpi;
import android.content.*;import android.net.Uri;import android.os.*;import android.provider.Settings;import java.util.*;import java.util.concurrent.*;import java.util.function.*;import org.json.*;
/** Actual bootstrap, provider, permission policy and connected Wi-Fi policy; Android APIs are fixtures. */
public final class UsbActivationHarness {
 static int checks;
 interface Checked {void run()throws Exception;}
 static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
 static void reject(Checked body,String name)throws Exception{boolean rejected=false;try{body.run();}catch(Exception expected){rejected=true;}check(rejected,name);}
 static void await(BooleanSupplier c)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!c.getAsBoolean()){if(System.nanoTime()>end)throw new AssertionError("Fixture deadline");Thread.sleep(5);}}
 static CompletableFuture<String> usb(){return CompletableFuture.supplyAsync(()->{try{return EmbeddedAdb.launchUsbWorker();}catch(Exception e){throw new CompletionException(e);}});}
 static JSONObject request(String script)throws Exception{return new JSONObject(script.substring(script.indexOf('{'),script.indexOf('}')+1));}
 static String descriptor()throws Exception{return new JSONObject().put("protocol",WorkerChannel.PROTOCOL).put("port",12345).put("pid",1234).put("epoch",UUID.randomUUID().toString()).put("application_id",AppIdentity.PACKAGE_NAME).toString();}
 public static void main(String[] args)throws Exception{
  Context context=new Context();EmbeddedAdb.init(context);
  Settings.Global.value=0;Settings.Global.writes=0;context.permission=false;
  check(!AutoActivation.requestDebugging(context)&&Settings.Global.writes==0,"Unprivileged app wrote protected settings");
  context.permission=true;check(AutoActivation.requestDebugging(context)&&Settings.Global.value==1&&Settings.Global.writes==1,"Granted activation did not enable only Wi-Fi debugging");
  check("adb_wifi_enabled".equals(Settings.Global.lastKey),"Activation changed another global setting");
  AutoActivation.requestDebugging(context);check(Settings.Global.writes==1,"Already enabled debugging was rewritten");
  Settings.Global.denied=true;check(!AutoActivation.requestDebugging(context),"Provider denial was reported as success");Settings.Global.denied=false;
  Settings.Global.value=0;context.cm.wifi=true;AutoActivation.configure(context,false);check(!EmbeddedAdb.backgroundLaunchAllowed(),"Disabled auto option admitted a background activation");
  AutoActivation.configure(context,true);check(EmbeddedAdb.backgroundLaunchAllowed(),"Trusted Wi-Fi plus granted auto permission could not recover");
  context.cm.wifi=false;check(!EmbeddedAdb.backgroundLaunchAllowed(),"Only SIM caused an unrequested background hotspot experiment");
  context.permission=false;context.cm.wifi=true;check(!EmbeddedAdb.backgroundLaunchAllowed(),"Missing settings permission admitted auto-enable");context.permission=true;context.cm.wifi=false;
  Settings.Global.value=1;
  check(!EmbeddedAdb.backgroundLaunchAllowed(),"A stale enabled setting without Wi-Fi admitted background launch");
  UsbActivationProvider provider=new UsbActivationProvider();
  String address="content://"+AppIdentity.PACKAGE_NAME+".usb/start";Uri uri=Uri.parse(address);
  reject(()->provider.openFile(uri,"r"),"USB script available without explicit pending operation");
  check(!EmbeddedAdb.paired(),"Fixture unexpectedly paired before first USB activation");CompletableFuture<String> ready=usb();await(EmbeddedAdb::busy);
  Binder.uid=10374;reject(()->provider.openFile(uri,"r"),"App UID read privileged USB script");Binder.uid=0;reject(()->provider.openFile(uri,"r"),"Root substituted for required shell UID");Binder.uid=2000;
  reject(()->provider.openFile(Uri.parse("content://x/other"),"r"),"USB provider accepted a different operation");reject(()->provider.openFile(uri,"w"),"USB provider allowed mutation");
  String script=new String(provider.openFile(uri,"r").readAll(),java.nio.charset.StandardCharsets.UTF_8);JSONObject input=request(script);String cap=input.getString("capability"),nonce=input.getString("bootstrap_nonce");
  check(input.getInt("app_uid")==10374&&input.getInt("protocol")==18,"USB bootstrap targeted another UID/protocol");
  check(script.contains("/data/app/igareck/base.apk")&&script.contains("setsid -w"),"USB script lost verified own APK/detached launch");
  String shell="content read --uri "+address+" | sh";
  check(UsbCommands.WINDOWS.devices().equals(".\\adb.exe devices -l")
      &&UsbCommands.WINDOWS.start().equals(".\\adb.exe -d shell \""+shell+"\""),"Incorrect PowerShell USB commands");
  check(UsbCommands.UNIX.devices().equals("./adb devices -l")
      &&UsbCommands.UNIX.start().equals("./adb -d shell '"+shell+"'"),"Incorrect Linux/macOS USB commands");
  check(!shell.contains(cap)&&!shell.contains(nonce),"Clipboard command exposed the USB request");
  reject(()->provider.openFile(uri,"r"),"One-time USB script was issued twice");
  String description=descriptor();EmbeddedAdb.bootstrapReply(nonce,description,"0".repeat(64));Thread.sleep(25);check(!ready.isDone(),"Forged USB receipt finished activation");
  EmbeddedAdb.bootstrapReply(UUID.randomUUID().toString(),description,AdbBootstrap.proof(cap,nonce,description));Thread.sleep(25);check(!ready.isDone(),"Stale nonce finished USB activation");
  EmbeddedAdb.bootstrapReply(nonce,description,AdbBootstrap.proof(cap,nonce,description));JSONObject returned=new JSONObject(ready.get(2,TimeUnit.SECONDS));check(cap.equals(returned.getString("capability")),"Authenticated USB bootstrap failed without prior Wi-Fi pairing");check(!EmbeddedAdb.busy(),"Completed USB wait left operation busy");
  reject(()->provider.openFile(uri,"r"),"Completed USB request remained readable");check(AppLog.lines.stream().noneMatch(line->line.contains(cap)),"USB capability leaked into logs");
  CompletableFuture<String> cancel=usb();await(EmbeddedAdb::busy);EmbeddedAdb.cancel();reject(()->cancel.get(2,TimeUnit.SECONDS),"USB wait ignored Cancel");check(!EmbeddedAdb.busy(),"Cancelled USB wait stayed busy");
  CompletableFuture<String> expired=usb();await(EmbeddedAdb::busy);SystemClock.offset+=181000;reject(()->expired.get(2,TimeUnit.SECONDS),"USB wait was not time bounded");SystemClock.offset=0;
  check(!EmbeddedAdb.busy(),"Timed-out USB activation stayed busy");
  EmbeddedAdb.pair(12345,"123456");context.cm.wifi=false;Settings.Global.value=1;
  try{
   EmbeddedAdb.launchWorker();throw new AssertionError("Paired activation without Wi-Fi was accepted");
  }catch(EmbeddedAdb.ActivationRequiredException e){
   check(e.getMessage().contains("Сопряжение сохранено")&&e.getMessage().contains("Wi-Fi")&&e.getMessage().contains("Запуск через USB"),"Activation requirement lost its guidance");
  }
  check(EmbeddedAdb.paired()&&!EmbeddedAdb.busy(),"Precondition changed pairing or left a pending activation");
  context.cm.wifi=true;
  int discoveries=context.nsd.scoped+context.nsd.legacy;
  reject(()->EmbeddedAdb.launchWorker(false,()->false),"Cancelled recovery started bootstrap");
  check(context.nsd.scoped+context.nsd.legacy==discoveries&&!EmbeddedAdb.busy(),"Cancelled recovery touched discovery or stayed busy");
  java.util.concurrent.atomic.AtomicBoolean keep=new java.util.concurrent.atomic.AtomicBoolean(true);
  CompletableFuture<String> automatic=CompletableFuture.supplyAsync(()->{try{return EmbeddedAdb.launchWorker(false,keep::get);}catch(Exception e){throw new CompletionException(e);}});
  await(EmbeddedAdb::busy);keep.set(false);reject(()->automatic.get(2,TimeUnit.SECONDS),"Automatic discovery ignored its own cancellation");
  check(!EmbeddedAdb.busy()&&!EmbeddedAdb.searching(),"Cancelled automatic discovery leaked NSD/busy state");
  System.out.println(new JSONObject().put("passed",true).put("checks",checks).put("production_bootstrap",true)
      .put("production_usb_provider",true).put("android_apis","explicit fixtures").put("physical_usb_or_samsung_tested",false)
      .put("windows_devices",UsbCommands.WINDOWS.devices()).put("windows_start",UsbCommands.WINDOWS.start())
      .put("unix_devices",UsbCommands.UNIX.devices()).put("unix_start",UsbCommands.UNIX.start()));
 }
}
