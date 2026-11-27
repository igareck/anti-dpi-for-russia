/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import android.content.SharedPreferences;
/** UDP is the master switch; Discord/STUN is optional within that relay. */
public final class ProxyOptions {
 public static void migrate(SharedPreferences prefs){
  String old=prefs.getString("discord_profile","D1");boolean valid=old.matches("D[1-8]");
  if(!prefs.getAll().containsKey("discord_enabled"))prefs.edit().putBoolean("discord_enabled",prefs.getBoolean("udp_enabled",false)&&valid&&prefs.getAll().containsKey("discord_profile")).apply();
  if(!valid)prefs.edit().putString("discord_profile","D1").apply();
  if(!prefs.getBoolean("udp_enabled",false))prefs.edit().putBoolean("discord_enabled",false).apply();
 }
 public static boolean discordEnabled(boolean udp,boolean requested){return udp&&requested;}
 public static String discordId(boolean enabled,int index){return enabled?"D"+(index+1):"D0";}
 public static String quicId(boolean udpEnabled,boolean discordEnabled,String selected){return "OFF".equals(selected)?"Q1":selected;}
 public static boolean quicActive(boolean relay,String quic){return relay&&!"OFF".equals(quic);}
 public static boolean discordActive(boolean relay,String discord){return relay&&!"D0".equals(discord);}
 public static String appliedLabel(String tcp,boolean relay,String quic,String discord){
  return TcpIds.display(tcp)+(quicActive(relay,quic)?" · "+quic:"")+(discordActive(relay,discord)?" · "+discord:"");
 }
 private ProxyOptions(){}
}
