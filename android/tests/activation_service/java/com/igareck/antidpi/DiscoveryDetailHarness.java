package com.igareck.antidpi;
import android.content.Context;
import android.net.Network;
import android.net.nsd.*;
import android.os.Build;
import android.provider.Settings;
import java.lang.reflect.*;
import java.net.InetAddress;
import org.json.JSONObject;

/** Actual discovery event ordering, not just a copy of its implementation. */
public final class DiscoveryDetailHarness {
 static int checks;
 static void check(boolean b,String label){checks++;if(!b)throw new AssertionError(label);}
 static NsdServiceInfo info(String name,String type,int port)throws Exception{NsdServiceInfo i=new NsdServiceInfo();i.name=name;i.type=type;i.host=InetAddress.getByName("127.0.0.1");i.port=port;return i;}
 static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
 public static void main(String[] args)throws Exception{
  for(int sdk:new int[]{30,31,32,33,34,35}){
   Build.VERSION.SDK_INT=sdk;Context c=new Context();AdbDiscovery d=new AdbDiscovery(c);d.start();
   check(sdk<33?c.nsd.legacy==2:c.nsd.scoped==2,"Missing SDK discovery branch "+sdk);
   NsdServiceInfo connect=info("same-guid","_adb-tls-connect._tcp.",39751),pair=info("same-guid","_adb-tls-pairing._tcp.",35519);
   NsdManager.DiscoveryListener cp=c.nsd.listeners.get(1),pp=c.nsd.listeners.get(0);
   cp.onServiceFound(connect);pp.onServiceFound(pair);check(d.connectPort==39751&&d.pairingPort==35519,"Two service roles not tracked");
   pp.onServiceLost(pair);check(d.connectPort==39751&&d.pairingPort==0,"Closing pairing window cleared connection port");
   pp.onServiceFound(pair);cp.onServiceLost(connect);check(d.connectPort==0&&d.pairingPort==35519,"Connection loss cleared pairing port");
   d.stop();check(d.connectPort==0&&d.pairingPort==0&&!d.running(),"Stop left discovery state");
  }
  Build.VERSION.SDK_INT=35;Context c=new Context();AdbDiscovery d=new AdbDiscovery(c);c.nsd.defer=true;d.start();
  NsdManager.DiscoveryListener cp=c.nsd.listeners.get(1),pp=c.nsd.listeners.get(0);
  NsdServiceInfo ci=info("guid","_adb-tls-connect._tcp.",39751),pi=info("guid","_adb-tls-pairing._tcp.",35519);
  cp.onServiceFound(ci);cp.onServiceLost(ci);c.nsd.waiting.get(0).onServiceResolved(ci);check(d.connectPort==0,"Late resolution resurrected a lost service");
  pp.onServiceFound(pi);cp.onServiceFound(ci);c.nsd.waiting.get(1).onResolveFailed(pi,0);check(c.nsd.waiting.size()==3,"Failed resolution blocked connection job");
  NsdServiceInfo emptyType=info("guid","",39751);c.nsd.waiting.get(2).onServiceResolved(emptyType);check(d.connectPort==39751,"Resolved callback dropped its listener-bound service role");
  d.stop();cp.onServiceFound(ci);check(d.connectPort==0,"Callback from stopped generation accepted");
  c=new Context();c.nsd.defer=true;d=new AdbDiscovery(c);d.start();pp=c.nsd.listeners.get(0);cp=c.nsd.listeners.get(1);
  pp.onServiceFound(pi);cp.onServiceFound(ci);Object old=field(d,"resolving");((Runnable)field(old,"timeout")).run();
  check(c.nsd.cancelled==1&&c.nsd.waiting.size()==2,"Timeout did not cancel and advance resolver");
  c.nsd.waiting.get(0).onServiceResolved(pi);check(d.pairingPort==0,"Timed-out callback accepted");c.nsd.waiting.get(1).onServiceResolved(ci);check(d.connectPort==39751,"Connection not found after timeout");d.stop();
  c=new Context();d=new AdbDiscovery(c);d.start();cp=c.nsd.listeners.get(1);
  NsdServiceInfo a=info("guid","_adb-tls-connect._tcp.",39751),b=info("guid","_adb-tls-connect._tcp.",40971);a.network=new Network(1);b.network=new Network(2);
  cp.onServiceFound(a);cp.onServiceFound(b);cp.onServiceLost(a);check(d.connectPort==40971,"Losing one network cleared another network's service");cp.onServiceLost(info("guid","_adb-tls-connect._tcp.",0));check(d.connectPort==0,"Unknown-network loss retained stale candidate");d.stop();
  AdbDiscoveryState state=new AdbDiscoveryState();AdbDiscoveryState.Ticket ticket=state.found(false,"a","");check(!state.resolved(ticket,39751,false),"Foreign address accepted");check(!state.resolved(ticket,65536,true),"Invalid port accepted");
  AdbDiscoveryState.Ticket newer=state.found(false,"a","");check(!state.resolved(ticket,39751,true)&&state.resolved(newer,40971,true),"Outdated resolution overwrote new record");
  state.clear();for(int i=0;i<32;i++)check(state.found(false,"a"+i,"")!=null,"Premature discovery bound");check(state.found(false,"overflow","")==null,"Unbounded discovery records");
  Build.VERSION.SDK_INT=35;Context fallback=new Context();AdbDiscovery refreshed=new AdbDiscovery(fallback);refreshed.start();
  NsdManager.DiscoveryListener previous=fallback.nsd.listeners.get(1);refreshed.refresh();
  check(fallback.nsd.legacy==2&&fallback.nsd.scoped==2,"Fallback did not register a fresh discovery");
  check(!new JSONObject(refreshed.snapshot()).getBoolean("physical_network_scope")&&new JSONObject(refreshed.snapshot()).getBoolean("unscoped_retry"),"Fallback scope missing from diagnostics");
  previous.onServiceFound(ci);check(refreshed.connectPort==0,"Fallback accepted a stale service callback");
  fallback.nsd.listeners.get(fallback.nsd.listeners.size()-1).onServiceFound(ci);
  check(refreshed.connectPort==39751,"Fallback did not report the local connection port");
  refreshed.stop();refreshed.start();check(fallback.nsd.scoped==4,"Normal discovery did not restore physical network scope");refreshed.stop();
  Context context=new Context();Settings.Global.value=0;check(WirelessDebugging.enabled(context)==0,"OFF observation failed");check(WirelessDebugging.guidance(true,0).contains("работают через локальный канал"),"Debugging off incorrectly stopped worker status");
  Settings.Global.value=1;check(WirelessDebugging.enabled(context)==1&&WirelessDebugging.unavailable(1).contains("включена"),"Enabled debugging misreported as disabled");
  Settings.Global.value=77;check(WirelessDebugging.enabled(context)==-1,"Unsupported setting value assumed valid");Settings.Global.denied=true;check(WirelessDebugging.enabled(context)==-1,"Denied observation assumed disabled");Settings.Global.denied=false;
  check("adb_wifi_enabled".equals(Settings.Global.lastKey),"Unexpected global setting requested");
  System.out.println(new JSONObject().put("passed",true).put("checks",checks).put("scope","Production discovery and read-only wireless-state helper, explicit Android NSD/settings fixtures. Android 11/12/13/14/15 code paths; no physical Samsung mDNS claim"));
 }
}
