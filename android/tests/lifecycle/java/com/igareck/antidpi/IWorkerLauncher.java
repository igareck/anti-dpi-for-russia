package com.igareck.antidpi;
import android.os.*;
import org.json.*;
public interface IWorkerLauncher {
 String launch(String token)throws Exception;
 int getUid()throws Exception;
 IBinder asBinder();
 final class Stub {
  public static boolean realChannel;
  public static WorkerChannel.Server server;
  public static IWorkerLauncher asInterface(IBinder b){
   final FakeTransport t=(FakeTransport)b;
   return new IWorkerLauncher(){
    public String launch(String token)throws Exception{
     if(!realChannel)return new JSONObject().put("fixture_epoch",t.epoch).put("epoch",t.epoch).put("capability",token).toString();
     server=new WorkerChannel.Server(token,t.epoch,new WorkerChannel.Endpoint(){
      public String call(String op,JSONObject a)throws Exception{
       switch(op){
        case "uid":return "2000";
        case "shutdown":t.die();return "{}";
        case "status":return t.status();
        case "logs":return t.drainLogs();
        case "metrics":return t.snapshotMetrics();
        case "stop":return t.stopProxy(a.getString("instance"));
        case "recover":return t.recoverProxy(a.getString("instance"),a.getString("epoch"));
        case "start":return t.startProxyUdpDiscord(a.getString("profile"),a.getInt("port"),a.getBoolean("verbose"),new Binder(),a.getString("instance"),a.getBoolean("udp"),a.getString("quic"),a.getString("discord"));
        default:throw new SecurityException("Fixture operation not implemented");
       }
      }
      public void ownerLost(){}
     });
     return new JSONObject().put("protocol",18).put("epoch",t.epoch).put("port",server.port()).put("capability",token).toString();
    }
    public int getUid(){return 2000;}
    public IBinder asBinder(){return t;}
   };
  }
 }
}
