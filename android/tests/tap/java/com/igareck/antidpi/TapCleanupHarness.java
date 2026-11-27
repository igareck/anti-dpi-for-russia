package com.igareck.antidpi;
import android.content.Context;import android.os.ParcelFileDescriptor;
import java.lang.reflect.Field;import org.json.*;
/** Actual TapSession cleanup, fault-injected manager methods and descriptor. */
public class TapCleanupHarness {
 public static class Tether {int calls;boolean fail;public void stopTethering(int kind)throws Exception{calls++;if(fail){fail=false;throw new java.io.IOException("stop tether failed once");}}}
 public static class Request {int calls;boolean fail;public void release()throws Exception{calls++;if(fail){fail=false;throw new java.io.IOException("release failed once");}}}
 public static class Ethernet {int calls;boolean fail;public void setIncludeTestInterfaces(boolean include)throws Exception{calls++;if(fail){fail=false;throw new java.io.IOException("restore failed once");}}}
 static void set(Object object,String field,Object value)throws Exception{Field f=object.getClass().getDeclaredField(field);f.setAccessible(true);f.set(object,value);}
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception{
  JSONArray cases=new JSONArray();
  for(int failure=0;failure<4;failure++){
   TapSession tap=new TapSession(new Context());Tether tether=new Tether();Request request=new Request();Ethernet eth=new Ethernet();ParcelFileDescriptor fd=new ParcelFileDescriptor();
   tether.fail=failure==0;request.fail=failure==1;eth.fail=failure==2;fd.fail=failure==3;
   set(tap,"tetheringManager",tether);set(tap,"tetherRequested",true);set(tap,"tetheredInterfaceRequest",request);
   set(tap,"ethernetManager",eth);set(tap,"testInterfacesIncluded",true);set(tap,"tapFd",fd);set(tap,"tapName","testtap9");
   tap.close();check(!new JSONObject(tap.progress()).getBoolean("cleanup_ok"),"False first acknowledgement");
   tap.close();check(new JSONObject(tap.progress()).getBoolean("cleanup_ok"),"Failed resource was not retried");
   check(tether.calls==(failure==0?2:1)&&request.calls==(failure==1?2:1)&&eth.calls==(failure==2?2:1)&&fd.calls==(failure==3?2:1),"Successful operations were repeated or failed operation lost");
   tap.close();check(new JSONObject(tap.progress()).getBoolean("cleanup_ok"),"Idempotent third cleanup failed");
   cases.put(new JSONObject().put("failed_operation",failure).put("passed",true));
  }
  System.out.println(new JSONObject().put("passed",true).put("count",cases.length()).put("tests",cases).put("scope","Actual TapSession.cleanupAll and close; Android manager/descriptor fixtures, no device tethering claim."));
 }
}
