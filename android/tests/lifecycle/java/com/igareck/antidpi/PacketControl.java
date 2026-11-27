package com.igareck.antidpi;
import org.json.*;public final class PacketControl {
 public static String reply;public static int calls;public static String instance,epoch;
 static JSONObject stop(String id,String oldEpoch)throws Exception{calls++;instance=id;epoch=oldEpoch;if(reply==null)throw new java.io.IOException("fixture channel unavailable");return new JSONObject(reply);}
 interface Stop{String run(String id)throws Exception;}
 static final class Server{Server(int uid,String id,String epoch,Stop stop){}Server(int uid,String id,String epoch,Stop stop,java.util.function.Consumer<String> logger){}public void close(){}}
}
