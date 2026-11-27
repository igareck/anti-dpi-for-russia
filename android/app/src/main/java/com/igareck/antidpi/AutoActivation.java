/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.*;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.SupplicantState;
import android.provider.Settings;
import org.json.JSONObject;

/** Only user-enabled activation writes adb_wifi_enabled. No device-wide watchdog. */
final class AutoActivation {
 static boolean permission(Context c){return c.checkSelfPermission(ActivationPermission.WRITE)==PackageManager.PERMISSION_GRANTED;}
 static SharedPreferences prefs(Context c){return c.getSharedPreferences("activation_options",Context.MODE_PRIVATE);}
 static boolean automatic(Context c){return prefs(c).getBoolean("auto_debug",true);}
 static boolean restoreEnabled(Context c){return prefs(c).getBoolean("restore_enabled",true);}
 static boolean restorePaused(Context c){return prefs(c).getBoolean("restore_paused",false);}
 static boolean restoreWanted(Context c){return restoreEnabled(c)&&!restorePaused(c);}
 static boolean established(Context c){return prefs(c).getBoolean("restore_established",false)||EmbeddedAdb.paired();}
 static void markEstablished(Context c){if(!prefs(c).getBoolean("restore_established",false))prefs(c).edit().putBoolean("restore_established",true).apply();}
 static void setRestoreEnabled(Context c,boolean enabled){prefs(c).edit().putBoolean("restore_enabled",enabled).apply();}
 static void setRestorePaused(Context c,boolean paused){if(!prefs(c).edit().putBoolean("restore_paused",paused).commit())throw new IllegalStateException("Не удалось сохранить настройку восстановления сетевого модуля");}
 static void configure(Context c,boolean auto){prefs(c).edit().putBoolean("auto_debug",auto).apply();}
 // A Wi-Fi transport alone is not a station association: it can be a local
 // network, a test network or an OEM hotspot-related capability. Do not use
 // SSID/BSSID/networkId here: Android may redact them without location access.
 @SuppressWarnings("deprecation")
 static String wifiStationState(Context c){try{
  WifiManager wm=c.getSystemService(WifiManager.class);
  if(wm==null)return "UNAVAILABLE";
  if(!wm.isWifiEnabled())return "RADIO_OFF";
  WifiInfo info=wm.getConnectionInfo();
  return info==null||info.getSupplicantState()==null?"UNAVAILABLE":info.getSupplicantState().name();
 }catch(Exception e){return "UNAVAILABLE";}}
 static boolean wifiNetworkPresent(Context c){try{
  ConnectivityManager cm=c.getSystemService(ConnectivityManager.class);
  if(cm!=null)for(Network n:cm.getAllNetworks()){NetworkCapabilities caps=cm.getNetworkCapabilities(n);if(caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))return true;}
 }catch(Exception ignored){}return false;}
 static boolean wifiConnected(Context c){return SupplicantState.COMPLETED.name().equals(wifiStationState(c))&&wifiNetworkPresent(c);}
 static boolean requestDebugging(Context c){
  if(!permission(c))return false;
  try{return WirelessDebugging.enabled(c)==1||Settings.Global.putInt(c.getContentResolver(),"adb_wifi_enabled",1);}
  catch(Exception e){AppLog.line("ADB_AUTO_ENABLE","System refused activation setting: "+e.getClass().getSimpleName());return false;}
 }
 static boolean disableDebugging(Context c){
  if(!permission(c))return false;
  try{return Settings.Global.putInt(c.getContentResolver(),"adb_wifi_enabled",0);}
  catch(Exception e){AppLog.line("ADB_AUTO_ENABLE","System refused wireless-debugging reset: "+e.getClass().getSimpleName());return false;}
 }
 static String snapshot(Context c){try{return new JSONObject().put("permission",permission(c)).put("auto_enable",automatic(c)).put("wifi_connected",wifiConnected(c)).put("wifi_station_state",wifiStationState(c)).put("wifi_transport_present",wifiNetworkPresent(c)).put("restore_enabled",restoreEnabled(c)).put("restore_paused",restorePaused(c)).put("restore_established",established(c)).toString();}catch(Exception e){return "{}";}}
 private AutoActivation(){}
}
