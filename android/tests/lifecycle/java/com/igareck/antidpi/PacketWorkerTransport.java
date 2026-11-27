package com.igareck.antidpi;
import android.os.*;
import org.json.*;
import rikka.shizuku.Shizuku;
/** Socket boundary for old service race fixtures. Real sockets have a separate harness. */
final class PacketWorkerTransport extends ITransportService.Stub {
 final FakeTransport t;
 PacketWorkerTransport(JSONObject descriptor)throws Exception{t=Shizuku.target;if(!t.isBinderAlive()||!descriptor.getString("fixture_epoch").equals(t.epoch))throw new java.io.IOException("Fixture worker gone");}
 boolean available(){return t.isBinderAlive();}
 void detach(){}
 void confirmRetention(){}
 void shutdown(){t.die();}
 public int getUid(){return 2000;}
 public String status()throws Exception{return t.status();}
 public String drainLogs()throws Exception{return t.drainLogs();}
 public String snapshotMetrics()throws Exception{return t.snapshotMetrics();}
 public String stopProxy(String id)throws Exception{return t.stopProxy(id);}
 public String recoverProxy(String id,String epoch)throws Exception{return t.recoverProxy(id,epoch);}
 public String startProxy(String p,int port,boolean v,IBinder owner,String id)throws Exception{return t.startProxy(p,port,v,owner,id);}
 public String startProxyUdpDiscord(String p,int port,boolean v,IBinder owner,String id,boolean udp,String quic,String discord)throws Exception{return t.startProxyUdpDiscord(p,port,v,owner,id,udp,quic,discord);}
 public String startYoutube(int[] numbers,IBinder owner,String id)throws Exception{return t.startYoutube(numbers,owner,id);}
 public void destroy(){}
}
