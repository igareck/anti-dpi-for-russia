/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import java.io.IOException;
import java.util.UUID;
import org.json.*;

/** Fixed, stdin-only shell script: no terminal, arbitrary command or remote host. */
final class AdbBootstrap {
 static final String ACTION=AppIdentity.PACKAGE_NAME+".WORKER_READY";
 static int port(String text,boolean automatic)throws IOException{
  String value=text==null?"":text.trim();if(value.isEmpty()&&automatic)return 0;
  value=value.replaceFirst("^0+(?!$)","");
  if(!value.matches("[0-9]{1,5}"))throw new IOException("Порт должен быть числом 1024–65535");
  int p=Integer.parseInt(value);if(p<1024||p>65535)throw new IOException("Порт должен быть числом 1024–65535");return p;
 }
 static String pairingCode(String text)throws IOException{String code=text==null?"":text.replaceAll("[\\s-]","");if(!code.matches("[0-9]{6}"))throw new IOException("Введи шестизначный код сопряжения");return code;}
 static String quote(String value)throws IOException{if(value==null||value.indexOf('\0')>=0||value.indexOf('\n')>=0||value.indexOf('\r')>=0)throw new IOException("Invalid bootstrap path");return "'"+value.replace("'","'\"'\"'")+"'";}
 static String script(String apk,String nativeDir,int appUid,String capability,String nonce,boolean process64)throws Exception{
  WorkerChannel.validateToken(capability);if(!UUID.fromString(nonce).toString().equals(nonce)||appUid<10000)throw new IOException("Invalid bootstrap owner");
  String marker="IGARECK_"+nonce.replace("-","");
  JSONObject request=new JSONObject().put("protocol",WorkerChannel.PROTOCOL).put("capability",capability).put("app_uid",appUid).put("bootstrap_nonce",nonce);
  // Toybox forks when the incoming shell job is a group/session leader. The
  // worker checks the resulting session instead of blindly calling setsid twice.
  return "if [ \"$(id -u)\" != 2000 ]; then echo IGARECK_ADB_UID_ERROR; exit 1; fi\nif [ ! -x /system/bin/toybox ]; then echo IGARECK_WORKER_ERROR shell MissingToybox 0; exit 1; fi\n(\nexport CLASSPATH="+quote(apk)+"\nexport LD_LIBRARY_PATH="+quote(nativeDir)+"\n/system/bin/toybox setsid -w "+(process64?"/system/bin/app_process64":"/system/bin/app_process32")+" /system/bin --nice-name="+AppIdentity.PACKAGE_NAME+":packet_worker "+PacketWorker.class.getName()+" <<'"+marker+"'\n"+request+"\n"+marker+"\nresult=$?\nif [ \"$result\" != 0 ]; then echo IGARECK_WORKER_ERROR shell LaunchFailed 0; fi\n) &\nwait \"$!\"\n";
 }
 static String proof(String capability,String nonce,String descriptor)throws Exception{return WorkerChannel.mac(capability,"adb-bootstrap\n"+WorkerChannel.PROTOCOL+"\n"+nonce+"\n"+descriptor);}
 static JSONObject verify(String capability,String expectedNonce,String nonce,String descriptor,String auth)throws Exception{
  if(!expectedNonce.equals(nonce)||descriptor==null||descriptor.length()>16384||!WorkerChannel.equal(proof(capability,nonce,descriptor),auth))throw new IOException("Bootstrap receipt authentication failed");
  JSONObject value=new JSONObject(descriptor);if(value.getInt("protocol")!=WorkerChannel.PROTOCOL||value.getInt("pid")<=0||!value.optString("application_id").equals(AppIdentity.PACKAGE_NAME))throw new IOException("Unexpected bootstrap worker");
  new WorkerChannel.Client(new JSONObject(value.toString()).put("capability",capability));return value.put("capability",capability);
 }
 private AdbBootstrap(){}
}
