/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import java.util.*;

/** Current discovery takes precedence over a stale cache; manual input is tried first. */
final class AdbPorts {
 static int[] candidates(int manual,int discovered,int cached){
  return candidates(manual,discovered,0,cached);
 }
 static int[] candidates(int manual,int discovered,int system,int cached){
  LinkedHashSet<Integer> ports=new LinkedHashSet<>();
  for(int p:new int[]{manual,discovered,system,cached})if(p>=1024&&p<=65535)ports.add(p);
  int[] result=new int[ports.size()];int i=0;for(int p:ports)result[i++]=p;return result;
 }
 // Read-only optional hint used by AOSP itself. Hidden API/property access can
 // be denied on an OEM build; discovery and manual input remain available.
 static int systemPort(){
  try{Object value=Class.forName("android.os.SystemProperties").getMethod("get",String.class,String.class).invoke(null,"service.adb.tls.port","");
   int p=Integer.parseInt(String.valueOf(value));return p>=1024&&p<=65535?p:0;
  }catch(Exception|LinkageError ignored){return 0;}
 }
 private AdbPorts(){}
}
