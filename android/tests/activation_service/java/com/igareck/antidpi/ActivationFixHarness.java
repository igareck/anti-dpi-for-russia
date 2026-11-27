package com.igareck.antidpi;
import android.app.*;
import android.content.*;
import android.os.Build;
import android.net.nsd.*;
import android.system.ErrnoException;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Actual activation service/discovery with explicit framework-only boundaries. */
public final class ActivationFixHarness {
 static int checks;
 static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
 static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
 static Notification notification(AdbActivationService s,boolean alert)throws Exception{Method m=s.getClass().getDeclaredMethod("notification",boolean.class);m.setAccessible(true);return (Notification)m.invoke(s,alert);}
 static boolean has(Notification n,String label){return n.actions.stream().anyMatch(a->label.equals(a.label));}
 static void settled(AdbActivationService s)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while((Boolean)field(s,"operation")){if(System.nanoTime()>until)throw new AssertionError("Operation did not finish");Thread.sleep(5);}}
 static Intent intent(AdbActivationService s,String action){return new Intent(s,AdbActivationService.class).setAction(action);}
 static NsdServiceInfo info(String name,String type,String address,int port)throws Exception{NsdServiceInfo v=new NsdServiceInfo();v.name=name;v.type=type;v.host=InetAddress.getByName(address);v.port=port;return v;}
 public static void main(String[] args)throws Exception{
  ArrayList<AdbActivationService> services=new ArrayList<>();
  try{
   for(String action:new String[]{AdbActivationService.CANCEL,null}){
    AdbActivationService s=new AdbActivationService();services.add(s);s.onCreate();s.onStartCommand(action==null?null:intent(s,action),0,1);
    check(s.calls.equals(Arrays.asList("foreground","remove","stop")),"Cancel/null launch failed foreground promotion ordering");
   }
   AdbActivationService s=new AdbActivationService();services.add(s);s.onCreate();
   check(s.notifications.channel.importance==4&&!s.notifications.channel.id.equals("adb_activation"),"Existing default channel was reused instead of a new high-importance channel");
   s.onStartCommand(intent(s,AdbActivationService.SEARCH),0,2);
   Notification n=notification(s,false);check(has(n,"Ввести код"),"Pairing action absent");
   check(n.behavior==Notification.FOREGROUND_SERVICE_IMMEDIATE,"Foreground visibility may be deferred");
   for(Notification.Action a:n.actions){check(a.intent.foreground,"Notification action may restart service without foreground promotion");if(a.remote!=null)check((a.intent.flags&PendingIntent.FLAG_MUTABLE)!=0,"RemoteInput pending intent not mutable");else check((a.intent.flags&PendingIntent.FLAG_IMMUTABLE)!=0,"Non-input intent mutable");}
   EmbeddedAdb.port=35559;((Runnable)field(s,"refresh")).run();check(!s.notifications.last.onlyOnce,"New pairing port did not request a heads-up update");
   ((Runnable)field(s,"refresh")).run();check(s.notifications.last.onlyOnce,"Periodic refresh keeps alerting");
   PacketBridge.fail=true;s.onStartCommand(intent(s,AdbActivationService.PAIR).putExtra(AdbActivationService.CODE,"123456"),0,3);settled(s);
   n=notification(s,false);check(EmbeddedAdb.paired&&!has(n,"Ввести код")&&has(n,"Активировать"),"Successful pairing followed by activation error still asks for another code");
   PacketBridge.fail=false;s.onStartCommand(intent(s,AdbActivationService.ACTIVATE),0,4);settled(s);check(s.stoppedStartId==4&&s.calls.contains("remove"),"Successful activation did not end current foreground start");
   EmbeddedAdb.gate=new CountDownLatch(1);s.onStartCommand(intent(s,AdbActivationService.PAIR).putExtra(AdbActivationService.CODE,"123456"),0,7);s.onStartCommand(intent(s,AdbActivationService.ACTIVATE),0,8);EmbeddedAdb.gate.countDown();settled(s);check(s.stoppedStartId==8,"Ignored duplicate start left foreground service alive after success");EmbeddedAdb.gate=null;
   AdbActivationService cancel=new AdbActivationService();services.add(cancel);cancel.onCreate();EmbeddedAdb.gate=new CountDownLatch(1);
   cancel.onStartCommand(intent(cancel,AdbActivationService.PAIR).putExtra(AdbActivationService.CODE,"123456"),0,5);
   int launches=PacketBridge.calls;cancel.onStartCommand(intent(cancel,AdbActivationService.CANCEL),0,6);settled(cancel);check(PacketBridge.calls==launches,"Late pair completion launched a worker after cancellation");EmbeddedAdb.gate=null;
   ((Runnable)field(cancel,"timeout")).run();check(cancel.calls.get(cancel.calls.size()-2).equals("remove"),"Timeout did not remove foreground notification");
   for(String action:new String[]{AdbActivationService.USB,AdbActivationService.RELEASE}){
    AdbActivationService explicit=new AdbActivationService();services.add(explicit);explicit.onCreate();explicit.onStartCommand(intent(explicit,action),0,20);settled(explicit);
    check(explicit.calls.get(0).equals("foreground"),"USB/release missed foreground promotion");
    check(explicit.stoppedStartId==20&&explicit.calls.contains("remove"),"USB/release did not finish matching foreground operation");
   }
   Build.VERSION.SDK_INT=30;check(notification(s,false).behavior==0,"API 31 notification method used on Android 11");
   Context c=new Context();AdbDiscovery d=new AdbDiscovery(c);d.start();check(c.nsd.legacy==2,"Android 11 discovery fallback missing");d.stop();
   Build.VERSION.SDK_INT=35;c=new Context();d=new AdbDiscovery(c);d.start();
   check(c.nsd.scoped==2&&c.nsd.request.transports.containsAll(Arrays.asList(1,3))&&c.nsd.request.capabilities.contains(15),"Discovery not scoped to physical local networks");
   NsdManager.DiscoveryListener listener=c.nsd.listeners.get(1);
   listener.onServiceFound(info("foreign","_adb-tls-connect._tcp.","192.0.2.1",40031));check(d.connectPort==0,"Foreign ADB service accepted");
   NsdServiceInfo local=info("own","_adb-tls-connect._tcp.","127.0.0.1",40031);listener.onServiceFound(local);check(d.connectPort==40031,"Own resolved service ignored when API 34 host list is empty");
   listener.onServiceLost(local);check(d.connectPort==0,"Lost service left a stale port");d.stop();listener.onServiceFound(local);check(d.connectPort==0,"Stale discovery callback changed a stopped session");
   check(Arrays.equals(AdbPorts.candidates(0,40031,33723),new int[]{40031,33723}),"Cached old port wins over current discovery");
   check(Arrays.equals(AdbPorts.candidates(33723,40031,33723),new int[]{33723,40031}),"Manual candidate or fallback deduplication changed");
   check(AdbPorts.candidates(0,1,65536).length==0,"Invalid port candidate accepted");
   String secret="dummy-sensitive-bootstrap-content";
   String marker=WorkerFailure.marker("session",new java.lang.reflect.InvocationTargetException(new ErrnoException(secret,1)));
   check(marker.equals("IGARECK_WORKER_ERROR session ErrnoException 1"),"Root errno/phase lost");
   check(!marker.contains(secret)&&WorkerFailure.description(marker).contains("Повторный код не нужен"),"Error leaks input or wrongly asks to pair");
   check(!WorkerFailure.valid(marker+"\n"+secret)&&!WorkerFailure.valid("IGARECK_WORKER_ERROR session Exception 1 "+secret),"Unbounded/extra bootstrap error accepted");
   check(!WorkerFailure.marker("unknown",new Exception(secret)).contains(secret),"Generic error serialized a private message");
   System.out.println(new JSONObject().put("passed",true).put("checks",checks).put("scope","Production activation Service, discovery, port policy and error sanitizer; explicit Android notification/NSD boundary fixtures, no Samsung popup claim"));
  }finally{for(AdbActivationService s:services)s.onDestroy();}
 }
}
