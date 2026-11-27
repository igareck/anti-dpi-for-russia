package com.igareck.antidpi;
import java.io.*;import java.util.*;import android.content.Context;import org.json.*;
/** Linux /proc parser on real owned host processes; UID is mapped by the Os fixture. */
public class ProcessIdentityHarness {
 public static void main(String[] args)throws Exception{
  PacketRecovery.AndroidHost host=new PacketRecovery.AndroidHost(new Context());String id=UUID.randomUUID().toString();
  Process own=new ProcessBuilder(args[0],"--igareck-instance="+id,"--igareck-socket=igareck-tap-"+id).start();
  Process unrelated=new ProcessBuilder(args[0],"--igareck-instance="+UUID.randomUUID(),"--igareck-socket=igareck-tap-"+id).start();
  Process wrongSocket=new ProcessBuilder(args[0],"--igareck-instance="+id,"--igareck-socket=foreign").start();
  try{
   List<Integer> found=host.nativePids(id);
   if(found.size()!=1||found.get(0)!=(int)own.pid())throw new AssertionError("Incorrect process ownership "+found);
   String ticks=host.startTicks((int)own.pid());if(ticks.isEmpty()||!ticks.equals(host.startTicks((int)own.pid())))throw new AssertionError("Unstable identity");
   own.destroy();own.waitFor();if(!host.nativePids(id).isEmpty()||!host.startTicks((int)own.pid()).isEmpty())throw new AssertionError("Dead process still present");
   System.out.println(new JSONObject().put("passed",true).put("checks",4).put("scope","Actual AndroidHost /proc parser on real Linux processes; fixture maps current host UID to shell UID 2000, Android SELinux permissions untested."));
  }finally{own.destroyForcibly();unrelated.destroyForcibly();wrongSocket.destroyForcibly();own.waitFor();unrelated.waitFor();wrongSocket.waitFor();}
 }
}
