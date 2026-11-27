#!/usr/bin/env python3
"""Production ADB recovery against simulated stale adbd, system hints and Wi-Fi authorization."""
import argparse,json,pathlib,subprocess
p=argparse.ArgumentParser();p.add_argument('--jdk',required=True);p.add_argument('--json-jar',required=True);a=p.parse_args()
r=pathlib.Path(__file__).resolve().parents[1];out=r/'build/adb-runtime';out.mkdir(parents=True,exist_ok=True)
base=r/'tests/activation_service/java';over=r/'tests/usb_activation/java'
files={str(f.relative_to(base)):f for f in (base/'android').rglob('*.java')}
files.update({str(f.relative_to(over)):f for f in over.rglob('*.java') if f.name!='UsbActivationHarness.java'})
fixtures={
'android/os/SystemClock.java':'''package android.os;public class SystemClock {public static volatile long offset;public static long elapsedRealtime(){return System.nanoTime()/1000000*20+offset;}}''',
'android/os/SystemProperties.java':'''package android.os;public class SystemProperties {public static volatile String port="";public static String get(String k,String d){return port;}}''',
'android/provider/Settings.java':'''package android.provider;import java.util.*;public class Settings {public static class Global {public static volatile int value=-1,writes;public static boolean denied,revive;public static String lastKey;public static final List<Integer> history=new java.util.concurrent.CopyOnWriteArrayList<>();public static int getInt(android.content.ContentResolver r,String k,int d){lastKey=k;if(denied)throw new SecurityException();return value;}public static boolean putInt(android.content.ContentResolver r,String k,int v){if(denied)throw new SecurityException();if(!k.equals("adb_wifi_enabled"))throw new AssertionError("Unrelated setting changed");lastKey=k;writes++;history.add(v);value=v;if(v==0)android.os.SystemProperties.port="";if(v==1&&revive)android.os.SystemProperties.port="22222";return true;}}}''',
'com/igareck/antidpi/AdbClient.java':'''package com.igareck.antidpi;import android.content.Context;import java.io.*;import java.util.*;import io.github.muntashirakon.adb.AdbStream;final class AdbClient implements AutoCloseable {static final List<Integer> calls=new java.util.concurrent.CopyOnWriteArrayList<>();static int good=22222;AdbClient(Context c,boolean pairing){}boolean pair(String h,int p,String code){return true;}void setTimeout(long n,java.util.concurrent.TimeUnit u){}boolean connectLocal(int p)throws IOException{calls.add(p);if(p!=good)throw new IOException("fixture stale port");return true;}AdbStream openStream(String s){return new AdbStream();}public void close()throws IOException{}}''',
'io/github/muntashirakon/adb/AdbStream.java':'''package io.github.muntashirakon.adb;import java.io.*;import org.json.*;import java.util.UUID;public class AdbStream implements AutoCloseable {public InputStream openInputStream(){return new ByteArrayInputStream(new byte[0]);}public OutputStream openOutputStream(){return new ByteArrayOutputStream(){public void flush()throws IOException{try{String s=toString("UTF-8");JSONObject q=new JSONObject(s.substring(s.indexOf('{'),s.indexOf('}')+1));String d=new JSONObject().put("protocol",18).put("port",12345).put("pid",1234).put("epoch",UUID.randomUUID().toString()).put("application_id","com.igareck.antidpi").toString();com.igareck.antidpi.AdbRuntimeHarness.reply(q,d);}catch(Exception e){throw new IOException(e);}}};}public void close()throws IOException{}}''',
'com/igareck/antidpi/AdbRuntimeHarness.java':r'''package com.igareck.antidpi;
import android.content.Context;import android.os.*;import android.provider.Settings;import java.util.*;import java.util.concurrent.*;import java.util.function.*;import org.json.*;
public class AdbRuntimeHarness {
 static int checks;static Context c;static void check(boolean ok,String s){checks++;if(!ok)throw new AssertionError(s);}
 static void await(BooleanSupplier p)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!p.getAsBoolean()){if(System.nanoTime()>until)throw new AssertionError("Fixture timeout");Thread.sleep(5);}}
 public static void reply(JSONObject q,String d)throws Exception{String cap=q.getString("capability"),nonce=q.getString("bootstrap_nonce");EmbeddedAdb.bootstrapReply(nonce,d,AdbBootstrap.proof(cap,nonce,d));}
 static void reset(){AdbClient.calls.clear();Settings.Global.history.clear();Settings.Global.value=1;Settings.Global.revive=false;Settings.Global.denied=false;SystemProperties.port="";c.permission=true;c.cm.wifi=true;PacketBridge.active=false;AutoActivation.configure(c,true);c.getSharedPreferences("embedded_adb",0).edit().putInt("last_connect_port",11111).putInt("connect_port",0).apply();}
 static void reject(Callable<String> op,String text)throws Exception{boolean failed=false;try{op.call();}catch(Exception expected){failed=true;}check(failed,text);}
 static CompletableFuture<String> run(boolean requested,BooleanSupplier keep){return CompletableFuture.supplyAsync(()->{try{return EmbeddedAdb.launchWorker(requested,keep);}catch(Exception e){throw new CompletionException(e);}});}
 public static void main(String[] args)throws Exception{
  c=new Context();EmbeddedAdb.init(c);EmbeddedAdb.pair(12345,"123456");
  reset();c.cm.wifi=true;c.wifi.station=()->false;
  reject(()->EmbeddedAdb.launchWorker(true),"Wi-Fi transport without station association admitted launch");
  check(AdbClient.calls.isEmpty()&&Settings.Global.history.isEmpty()&&c.wifi.starts==0,"No-Wi-Fi launch touched settings, ports, or hotspot");
  c.wifi.station=()->c.cm.wifi;
  reset();Settings.Global.revive=true;String d=EmbeddedAdb.launchWorker(true);
  check(new JSONObject(d).getInt("protocol")==18,"Recovery did not verify normal bootstrap");
  check(Settings.Global.history.equals(Arrays.asList(0,1)),"Stale enabled state was not cycled once");
  check(Collections.frequency(AdbClient.calls,11111)==1&&AdbClient.calls.contains(22222),"Recovery repeated cached port or missed fresh endpoint");
  check(c.getSharedPreferences("embedded_adb",0).getInt("last_connect_port",0)==22222,"Verified port not saved");
  check(EmbeddedAdb.paired()&&!EmbeddedAdb.busy()&&!EmbeddedAdb.searching(),"Recovery lost pairing or leaked operation");
  reset();SystemProperties.port="22222";EmbeddedAdb.configure(0,12346);EmbeddedAdb.launchWorker(true);
  check(AdbClient.calls.equals(Arrays.asList(12346,22222)),"Manual failure blocked system TLS hint");
  check(Settings.Global.history.isEmpty(),"Working endpoint was needlessly restarted");
  SystemProperties.port="garbage";check(AdbPorts.systemPort()==0,"Malformed property accepted");SystemProperties.port="-1";check(AdbPorts.systemPort()==0,"Server-failure property accepted");
  reset();c.permission=false;reject(()->EmbeddedAdb.launchWorker(true),"No-endpoint operation unexpectedly succeeded");
  check(Settings.Global.history.isEmpty()&&Collections.frequency(AdbClient.calls,11111)==1,"Permission denial caused writes or cached-port loop");
  reset();AutoActivation.configure(c,false);reject(()->EmbeddedAdb.launchWorker(true),"Disabled reset unexpectedly produced endpoint");check(Settings.Global.history.isEmpty(),"Disabled auto-debug setting ignored");
  reset();CompletableFuture<String> cancel=run(true,()->Settings.Global.history.isEmpty());reject(()->cancel.get(5,TimeUnit.SECONDS),"Cancellation during reset ignored");
  check(Settings.Global.history.equals(Arrays.asList(0))&&!EmbeddedAdb.busy(),"Cancelled reset re-enabled debugging or left busy state");
  reset();Settings.Global.revive=true;
  reject(()->EmbeddedAdb.launchWorker(false),"Background recovery cycled stale enabled debugging");
  check(Settings.Global.history.isEmpty()&&Collections.frequency(AdbClient.calls,11111)==1,"Background recovery rewrote settings or repeated the stale port");
  reset();c.cm.wifi=false;Settings.Global.value=0;
  reject(()->EmbeddedAdb.launchWorker(true),"Launch without Wi-Fi unexpectedly succeeded");
  check(Settings.Global.history.isEmpty()&&AdbClient.calls.isEmpty()&&c.wifi.starts==0,"Launch without Wi-Fi modified state");
  c.cm.wifi=true;Settings.Global.revive=true;
  check(new JSONObject(EmbeddedAdb.launchWorker(false)).getInt("protocol")==18,"Return of connected Wi-Fi did not recover");
  check(Settings.Global.history.equals(Arrays.asList(1)),"Background recovery cycled an already-sufficient Wi-Fi state");
  check(!EmbeddedAdb.busy()&&!EmbeddedAdb.searching(),"Wi-Fi startup leaked activation resources");
  System.out.println(new JSONObject().put("passed",true).put("checks",checks).put("production_embedded_adb_recovery",true).put("android_adbd","explicit fixtures; scaled clock").put("physical_samsung_tested",false));
 }
}'''}
for n,t in fixtures.items():f=out/'fixtures'/n;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(t);files[n]=f
prod=r/'app/src/main/java/com/igareck/antidpi'
src=list(files.values())+[prod/n for n in ['EmbeddedAdb.java','AdbBootstrap.java','AutoActivation.java','UsbActivationProvider.java','WorkerChannel.java','WirelessDebugging.java','AdbDiscovery.java','AdbDiscoveryState.java','AdbPorts.java','WorkerFailure.java','AppIdentity.java','ActivationPermission.java']]
subprocess.run([a.jdk+'/bin/javac','-encoding','UTF-8','-cp',a.json_jar,'-d',str(out),*map(str,src)],check=True)
run=subprocess.run([a.jdk+'/bin/java','-cp',str(out)+':'+a.json_jar,'com.igareck.antidpi.AdbRuntimeHarness'],capture_output=True,text=True,timeout=30)
(out/'stdout.log').write_text(run.stdout);(out/'stderr.log').write_text(run.stderr)
if run.returncode:raise RuntimeError(run.stderr)
v=json.loads(run.stdout.strip().splitlines()[-1]);(out/'result.json').write_text(json.dumps(v,indent=2)+'\n');print(json.dumps(v,indent=2))
