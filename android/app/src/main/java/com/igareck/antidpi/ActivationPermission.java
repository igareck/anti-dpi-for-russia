/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import android.content.Context;
import java.util.concurrent.TimeUnit;

/** Fixed self-grant, used only inside the verified shell worker. No arbitrary pm API. */
final class ActivationPermission {
 static final String WRITE="android.permission.WRITE_SECURE_SETTINGS";
 static void grantToOwnApp(Context context,int appUid){
  if(android.os.Process.myUid()!=2000||AppIdentity.resolve(context).uid!=appUid)return;
  java.lang.Process p=null;
  try{
   p=new ProcessBuilder("/system/bin/pm","grant","--user",Integer.toString(appUid/100000),AppIdentity.PACKAGE_NAME,WRITE).redirectErrorStream(true).start();
   if(!p.waitFor(5,TimeUnit.SECONDS))p.destroyForcibly();
   p.getInputStream().close();
  }catch(Exception ignored){if(p!=null)p.destroy();}
 }
 private ActivationPermission(){}
}
