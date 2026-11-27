package com.igareck.antidpi;
import android.content.Context;
import android.os.Build;
import android.net.nsd.*;
import java.net.InetAddress;
import org.json.JSONObject;

/** Replay the actual closing of a code window sharing the ADB instance name. */
public final class DiscoveryRegressionHarness {
 static NsdServiceInfo info(String type,int port)throws Exception{
  NsdServiceInfo i=new NsdServiceInfo();i.name="adb-fixture-same-guid";i.type=type;i.host=InetAddress.getByName("127.0.0.1");i.port=port;return i;
 }
 public static void main(String[] args)throws Exception{
  Build.VERSION.SDK_INT=35;Context context=new Context();AdbDiscovery discovery=new AdbDiscovery(context);discovery.start();
  NsdManager.DiscoveryListener pairing=context.nsd.listeners.get(0),connect=context.nsd.listeners.get(1);
  NsdServiceInfo c=info("_adb-tls-connect._tcp.",39751),p=info("_adb-tls-pairing._tcp.",35519);
  connect.onServiceFound(c);pairing.onServiceFound(p);
  boolean both=discovery.connectPort==39751&&discovery.pairingPort==35519;
  pairing.onServiceLost(p);
  boolean preserved=discovery.connectPort==39751&&discovery.pairingPort==0;
  discovery.stop();
  System.out.println(new JSONObject().put("both_ports_initially_found",both).put("connection_port_survives_pairing_window_close",preserved).put("passed",both&&preserved));
 }
}
