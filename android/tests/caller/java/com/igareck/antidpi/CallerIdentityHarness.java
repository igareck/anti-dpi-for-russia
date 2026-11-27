package com.igareck.antidpi;
import android.content.Context;
import android.content.pm.*;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import java.io.*;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.*;

/** Real NativePacketService + real TapSession; only Android/UID/resource boundaries are fixtures. */
public final class CallerIdentityHarness {
 static final int OLD_UID=10123,APP_UID=10456,FOREIGN_UID=10789;
 static boolean oldInstalled=true;
 static int checks;
 static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static void set(Object o,String n,Object v)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
 static Object get(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 static final class FixtureContext extends Context {
  public PackageManager getPackageManager(){return new PackageManager(){
   public ApplicationInfo getApplicationInfo(String name,int flags){
    ApplicationInfo info=new ApplicationInfo();
    if(name.equals("com.igareck.antidpi"))info.uid=APP_UID;
    else if(oldInstalled&&name.equals("com.igareck.antidpi"))info.uid=OLD_UID;
    else throw new IllegalArgumentException("No such installed package: "+name);
    info.sourceDir="fixture.apk";info.nativeLibraryDir="unused";return info;
   }
  };}
 }
 static final class Child extends java.lang.Process {
  boolean alive=true;
  public void destroy(){alive=false;}
  public java.lang.Process destroyForcibly(){alive=false;return this;}
  public boolean isAlive(){return alive;}
  public int waitFor(){return 0;}
  public boolean waitFor(long timeout,TimeUnit unit){return !alive;}
  public int exitValue(){if(alive)throw new IllegalThreadStateException();return 0;}
  public InputStream getInputStream(){return new ByteArrayInputStream(new byte[0]);}
  public InputStream getErrorStream(){return new ByteArrayInputStream(new byte[0]);}
  public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
 }
 static final class Owned {
  final NativePacketService service=new NativePacketService(new FixtureContext());
  final TapSession tap=new TapSession(new FixtureContext());
  final Child child=new Child();final String id=UUID.randomUUID().toString();
  final TapCleanupHarness.Tether tether=new TapCleanupHarness.Tether();
  final TapCleanupHarness.Request request=new TapCleanupHarness.Request();
  final TapCleanupHarness.Ethernet ethernet=new TapCleanupHarness.Ethernet();
  final ParcelFileDescriptor descriptor=new ParcelFileDescriptor();
  Owned()throws Exception{
   set(service,"tap",tap);set(service,"child",child);set(service,"instance",id);set(service,"state","RUNNING");
   set(tap,"tetheringManager",tether);set(tap,"tetherRequested",true);set(tap,"tetheredInterfaceRequest",request);
   set(tap,"ethernetManager",ethernet);set(tap,"testInterfacesIncluded",true);set(tap,"tapFd",descriptor);set(tap,"tapName","testtap9");
  }
  void stopped(JSONObject status)throws Exception{
   check(status.getString("state").equals("STOPPED")&&!status.getBoolean("native_alive"),"Stop did not stop owned child");
   check(tether.calls==1&&request.calls==1&&ethernet.calls==1&&descriptor.calls==1,"Owned TAP cleanup was incomplete or repeated");
   check(status.getJSONObject("tap").getBoolean("cleanup_ok"),"Cleanup not acknowledged");
  }
 }
 static void denied(Runnable action,String message){boolean denied=false;try{action.run();}catch(SecurityException e){denied=true;}check(denied,message);}
 public static void main(String[] args)throws Exception{
  boolean baseline=args.length>0&&args[0].equals("baseline");
  Binder.setCallingUid(2000);Owned own=new Owned();Binder.setCallingUid(APP_UID);
  if(baseline){
   boolean reproduced=false;
   try{own.service.stopProxy(own.id);}catch(SecurityException e){reproduced=e.getMessage().equals("Caller is not this app or Shizuku");}
   check(reproduced,"The framework accepted a mismatched caller identity");
   check(own.child.alive&&own.tether.calls==0,"Baseline did not preserve the observed running child");
   check((Integer)get(own.tap,"ownerUid")==OLD_UID,"Original TAP did not resolve old app UID");
   System.out.println(new JSONObject().put("baseline_reproduced",true).put("wrong_old_uid",OLD_UID).put("actual_app_uid",APP_UID).put("native_child_kept_running",own.child.alive).put("checks",checks));return;
  }
  check((Integer)get(own.tap,"ownerUid")==APP_UID,"TAP owner does not match the installed app");
  own.stopped(new JSONObject(own.service.stopProxy(own.id)));
  check(Binder.getCallingUid()==APP_UID,"Stop leaked cleared Binder identity");
  own.stopped(new JSONObject(own.service.stopProxy(own.id)));
  oldInstalled=false;Binder.setCallingUid(2000);Owned noOld=new Owned();Binder.setCallingUid(APP_UID);
  noOld.stopped(new JSONObject(noOld.service.stopProxy(noOld.id)));
  check(Binder.getCallingUid()==APP_UID,"Stop with absent old app changed caller identity");
  oldInstalled=true;Binder.setCallingUid(2000);Owned unauthorized=new Owned();
  for(int uid:new int[]{OLD_UID,FOREIGN_UID}){
   Binder.setCallingUid(uid);denied(()->unauthorized.service.stopProxy(unauthorized.id),"Foreign/old app can stop the new app");
   check(unauthorized.child.alive&&unauthorized.tether.calls==0,"Rejected caller changed resources");
   check(Binder.getCallingUid()==uid,"Rejected call changed identity");
   denied(()->unauthorized.tap.cancel(),"TAP accepted foreign/old UID");
  }
  Binder.setCallingUid(APP_UID);new TapSession(new FixtureContext()).cancel();check(Binder.getCallingUid()==APP_UID,"Direct owned TAP cancel changed identity");
  Binder.setCallingUid(2000);unauthorized.stopped(new JSONObject(unauthorized.service.stopProxy(unauthorized.id)));
  check(Binder.getCallingUid()==2000,"Shell Stop changed identity");
  Owned stale=new Owned();Binder.setCallingUid(APP_UID);JSONObject staleReply=new JSONObject(stale.service.stopProxy(UUID.randomUUID().toString()));
  check(stale.child.alive&&stale.tether.calls==0&&!((Boolean)get(stale.tap,"cancelled")),"Stale instance cancelled a new TAP");
  stale.stopped(new JSONObject(stale.service.stopProxy(stale.id)));
  check(Binder.getCallingUid()==APP_UID,"Final identity not restored");
  for(boolean udp:new boolean[]{false,true}){
   boolean rejected=false;
   try{stale.service.startProxyUdpDiscord("T20",1080,false,new Binder(),UUID.randomUUID().toString(),udp,udp?"OFF":"Q1","D1");}
   catch(IllegalArgumentException expected){rejected=true;}
   check(rejected,"Native service accepted Discord without master UDP/QUIC");
  }
  NativePacketService verbose=new NativePacketService(new FixtureContext());
  java.lang.reflect.Method log=NativePacketService.class.getDeclaredMethod("log",String.class);log.setAccessible(true);
  for(int i=0;i<256;i++)log.invoke(verbose,i+" "+"\u0001".repeat(4090));
  java.util.Set<String> seen=new java.util.HashSet<>();int chunks=0;
  for(;;){String batch=verbose.drainLogs();JSONArray lines=new JSONArray(batch);if(lines.length()==0)break;chunks++;
   check(new JSONObject().put("payload",batch).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<1048576,"Verbose logs exceeded control frame");
   for(int i=0;i<lines.length();i++)seen.add(lines.getString(i).split(" ",2)[0]);
  }
  check(seen.size()==256&&chunks>1,"Chunked log transfer lost queued lines");
  System.out.println(new JSONObject().put("passed",true).put("checks",checks).put("old_and_new_app_uid_differ",true).put("old_app_absent_also_passes",true).put("foreign_uid_rejected",true).put("scope","Actual NativePacketService + actual TapSession; host Binder UID/Android manager/descriptor fixtures. No physical Android Binder or SELinux claim."));
 }
}
