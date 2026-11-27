package com.igareck.antidpi;
import android.net.*;import android.os.Process;
import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.concurrent.atomic.*;import org.json.*;
/** Actual PacketControl implementation, real loopback socket I/O; Android credential and FD/poll APIs are explicit fixtures. */
public final class ControlHarness {
 interface Checked{void run()throws Exception;}static JSONArray results=new JSONArray();
 static String id(){return UUID.randomUUID().toString();}static void check(boolean value,String msg){if(!value)throw new AssertionError(msg);}
 static void failed(Checked fn)throws Exception{try{fn.run();}catch(Exception expected){return;}throw new AssertionError("Expected protocol rejection");}
 static void test(String name,Checked body)throws Exception{LocalSocket.clientUid=10364;LocalSocket.serverUid=2000;Process.uid=2000;body.run();results.put(new JSONObject().put("name",name).put("passed",true));}
 static String proof(String instance,String epoch)throws Exception{return new JSONObject().put("state","STOPPED").put("instance",instance).put("service_epoch",epoch).put("native_alive",false).put("workspace_cleanup_ok",true).put("tap",new JSONObject().put("cleanup_ok",true)).toString();}
 static void rejectedRaw(String endpoint,JSONObject req)throws Exception{try(LocalSocket s=new LocalSocket()){
  s.connect(new LocalSocketAddress(endpoint,LocalSocketAddress.Namespace.ABSTRACT));s.setSoTimeout(1000);
  try{s.getOutputStream().write((req.toString()+"\n").getBytes(StandardCharsets.UTF_8));s.getOutputStream().flush();check(s.getInputStream().read()==-1,"Rejected request received a response");}catch(java.net.SocketException closed){}
 }}
 static JSONObject request(String instance,String epoch)throws Exception{return new JSONObject().put("op","stop").put("instance",instance).put("service_epoch",epoch).put("nonce",id());}
 public static void main(String[] args)throws Exception{
  test("valid app UID can Stop with no Shizuku or Binder dependency",()->{
   String instance=id(),epoch=id();AtomicInteger calls=new AtomicInteger();
   try(PacketControl.Server s=new PacketControl.Server(10364,instance,epoch,expected->{check(expected.equals(instance),"Wrong callback instance");calls.incrementAndGet();return proof(instance,epoch);})){
    JSONObject reply=PacketControl.stop(instance,epoch);check(reply.getString("state").equals("STOPPED")&&calls.get()==1,"Stop not acknowledged");
   }
  });
  test("a foreign app UID cannot invoke Stop",()->{
   String instance=id(),epoch=id();AtomicInteger calls=new AtomicInteger();
   try(PacketControl.Server s=new PacketControl.Server(10364,instance,epoch,expected->{calls.incrementAndGet();return proof(instance,epoch);})){
    LocalSocket.clientUid=10365;failed(()->PacketControl.stop(instance,epoch));check(calls.get()==0,"Foreign app stopped engine");
   }
  });
  test("client refuses a non-shell helper UID",()->{
   String instance=id(),epoch=id();try(PacketControl.Server s=new PacketControl.Server(10364,instance,epoch,expected->proof(instance,epoch))){LocalSocket.serverUid=10365;failed(()->PacketControl.stop(instance,epoch));}
  });
  for(String wrong:Arrays.asList("instance","epoch","op","nonce"))test("server rejects wrong "+wrong,()->{
   String instance=id(),epoch=id();AtomicInteger calls=new AtomicInteger();
   try(PacketControl.Server s=new PacketControl.Server(10364,instance,epoch,expected->{calls.incrementAndGet();return proof(instance,epoch);})){
    JSONObject q=request(instance,epoch);if(wrong.equals("instance"))q.put("instance",id());if(wrong.equals("epoch"))q.put("service_epoch",id());if(wrong.equals("op"))q.put("op","start");if(wrong.equals("nonce"))q.put("nonce","bad");
    rejectedRaw(PacketControl.name(instance),q);check(calls.get()==0,"Wrong identity or operation reached Stop");
   }
  });
  test("oversized request does not call Stop",()->{
   String instance=id(),epoch=id();AtomicInteger calls=new AtomicInteger();
   try(PacketControl.Server s=new PacketControl.Server(10364,instance,epoch,expected->{calls.incrementAndGet();return proof(instance,epoch);})){
    rejectedRaw(PacketControl.name(instance),request(instance,epoch).put("padding","x".repeat(1600)));check(calls.get()==0,"Oversized command accepted");
   }
  });
  test("closing the listening endpoint during Stop preserves the accepted acknowledgement",()->{
   String instance=id(),epoch=id();PacketControl.Server[] box=new PacketControl.Server[1];
   box[0]=new PacketControl.Server(10364,instance,epoch,expected->{box[0].close();return proof(instance,epoch);});
   try{check(PacketControl.stop(instance,epoch).getString("state").equals("STOPPED"),"Listener close lost acknowledgement");}finally{box[0].close();}
   failed(()->PacketControl.stop(instance,epoch));
  });
  test("client rejects an acknowledgement with a different nonce",()->{
   String instance=id(),epoch=id();try(LocalServerSocket s=new LocalServerSocket(PacketControl.name(instance))){
    Thread peer=new Thread(()->{try(LocalSocket c=s.accept()){
     new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8)).readLine();
     c.getOutputStream().write((new JSONObject(proof(instance,epoch)).put("control_nonce",id()).toString()+"\n").getBytes(StandardCharsets.UTF_8));
    }catch(Exception e){throw new RuntimeException(e);}});peer.start();failed(()->PacketControl.stop(instance,epoch));peer.join(2000);check(!peer.isAlive(),"Peer did not finish");
   }
  });
  test("helper endpoint requires shell UID",()->{Process.uid=10364;failed(()->new PacketControl.Server(10364,id(),id(),expected->"{}"));});
  test("noncanonical identities rejected before connecting",()->{failed(()->PacketControl.stop("N20",id()));failed(()->PacketControl.name("../../foreign"));});
  System.out.println(new JSONObject().put("passed",true).put("count",results.length()).put("tests",results).put("scope","Actual production PacketControl with real host socket I/O; abstract Unix namespace, Linux peer UID and Android poll use explicit fixtures, not device certification."));
 }
}
