package com.igareck.antidpi;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.*;

/** Actual journal/locking/recovery code; explicit fixtures only at OS observation boundaries. */
public final class RecoveryHarness {
 interface Checked {void run()throws Exception;}
 static JSONArray results=new JSONArray();
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static void rejected(Checked action)throws Exception{try{action.run();}catch(IOException expected){return;}throw new AssertionError("Expected unconfirmed recovery");}
 static void test(String name,Checked body)throws Exception{body.run();results.put(new JSONObject().put("name",name).put("passed",true));}
 static String id(){return UUID.randomUUID().toString();}
 static final class Host implements PacketRecovery.Host {
  int pid=101,restores,liveCountdown;boolean visibilityFails,childAlive;String iface="";
  Map<Integer,String> ticks=new HashMap<>();
  public int pid(){return pid;}
  public String startTicks(int pid){return ticks.getOrDefault(pid,"");}
  public List<Integer> nativePids(String instance){if(liveCountdown>0){liveCountdown--;return Arrays.asList(201);}return childAlive?Arrays.asList(201):Collections.emptyList();}
  public List<String> interfaces(){return iface.isEmpty()?Collections.emptyList():Arrays.asList(iface);}
  public void restoreVisibility()throws Exception{restores++;if(visibilityFails)throw new IOException("injected visibility failure");}
 }
 static File root()throws Exception{return Files.createTempDirectory("igareck-recovery-").toFile();}
 static File legacy(File root,String instance)throws Exception{
  File dir=new File(root,"igareck-antidpi-10364-"+instance);check(dir.mkdir(),"legacy directory");
  for(String name:Arrays.asList("zapret-lib.lua","zapret-antidpi.lua","igareck-actual-tls.lua"))Files.write(new File(dir,name).toPath(),new byte[]{1,2});return dir;
 }
 static PacketRecovery manager(File root,String epoch,Host host){return new PacketRecovery(root,10364,epoch,host);}
 static ProcessBuilder leaseFixture(String... args){
  List<String> command=new ArrayList<>(Arrays.asList(System.getProperty("java.home")+"/bin/java","-cp",System.getProperty("java.class.path"),RecoveryHarness.class.getName()));
  Collections.addAll(command,args);return new ProcessBuilder(command);
 }
 static String awaitOutput(Path output,String expected)throws Exception {
  long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
  while(System.nanoTime()<until){
   if(Files.exists(output)){String value=Files.readString(output);if(value.contains(expected))return value;}
   Thread.sleep(20);
  }
  throw new AssertionError("No output from verifier: "+expected);
 }
 static void run()throws Exception {
  test("verifier lease survives actual owner process death and receives kernel EOF",()->{
   Path output=root().toPath().resolve("verifier.out");String instance=id();
   Process owner=leaseFixture("lease-owner",output.toString(),instance,id()).start();
   try{
    String ready=awaitOutput(output,"IGARECK_STOP_VERIFIER_READY");
    check(owner.isAlive()&&!ready.contains("Released"),"Verifier ran before the owner released its pipe");
    owner.destroyForcibly();check(owner.waitFor(5,TimeUnit.SECONDS),"Fixture owner did not exit");
    awaitOutput(output,"Released "+instance);
   }finally{if(owner.isAlive())owner.destroyForcibly();}
  });
  test("independent verifier checks the exact abandoned journal and leaves unrelated files and Ethernet",()->{
   File root=root();String instance=id(),epoch=id();Host owner=new Host();owner.ticks.put(101,"10");
   PacketRecovery first=manager(root,epoch,owner);File dir=first.begin(instance),other=legacy(root,id());
   first.checkpoint(dir,"testtap17",true);
   Host next=new Host();next.pid=102;next.iface="eth0";
   try(PacketRecovery verifier=manager(root,epoch,next)){
    rejected(()->verifier.verifyReleasedRun(instance,epoch));check(dir.exists()&&next.restores==0,"Verifier ignored owner lock");
    first.close();
    rejected(()->verifier.verifyReleasedRun(instance,id()));check(dir.exists()&&next.restores==0,"Verifier ignored owner epoch");
    next.liveCountdown=2;
    verifier.verifyReleasedRun(instance,epoch);
    check(!dir.exists()&&other.isDirectory()&&next.restores==1&&next.liveCountdown==0&&next.iface.equals("eth0"),"Verifier did not safely clean its own resources");
   }
  });
  test("independent verifier defers confirmation while its TAP or visibility cleanup remains",()->{
   File root=root();String instance=id(),epoch=id();Host owner=new Host();
   File dir;
   try(PacketRecovery first=manager(root,epoch,owner)){dir=first.begin(instance);first.checkpoint(dir,"testtap17",true);}
   Host next=new Host();next.pid=102;next.iface="testtap17";
   try(PacketRecovery verifier=manager(root,epoch,next)){
    rejected(()->verifier.verifyReleasedRun(instance,epoch));check(dir.exists()&&next.restores==0,"Verifier ignored an existing TAP");
    next.iface="";next.visibilityFails=true;
    rejected(()->verifier.verifyReleasedRun(instance,epoch));check(dir.exists(),"Failed cleanup lost recovery journal");
    next.visibilityFails=false;verifier.verifyReleasedRun(instance,epoch);check(!dir.exists(),"Verifier could not confirm cleanup on retry");
   }
  });
  test("independent verification of a completed run never claims the new run's TAP or lock",()->{
   File root=root();String instance=id(),epoch=id();Host host=new Host();
   try(PacketRecovery prior=manager(root,epoch,host)){prior.finish(prior.begin(instance));}
   try(PacketRecovery next=manager(root,id(),host);PacketRecovery verifier=manager(root,epoch,host)){
    File active=next.begin(id());host.iface="testtap18";
    verifier.verifyReleasedRun(instance,epoch);
    check(active.exists()&&host.restores==0,"Verifier affected the new run");
   }
  });
  test("journal is committed before TAP; checkpoint survives helper restart",()->{
   File root=root();Host old=new Host();old.ticks.put(101,"10");String instance=id(),epoch=id();PacketRecovery first=manager(root,epoch,old);
   File dir=first.begin(instance);JSONObject owner=new JSONObject(Files.readString(new File(dir,"owner.json").toPath()));
   check(owner.getString("instance").equals(instance)&&!owner.getBoolean("visibility_requested"),"initial owner record");
   first.checkpoint(dir,"testtap17",true);first.close();Host next=new Host();next.pid=102;
   PacketRecovery second=manager(root,id(),next);JSONObject proof=second.sweep(instance,epoch);
   check(proof.getBoolean("verified")&&proof.getString("previous_instance").equals(instance)&&proof.getString("previous_service_epoch").equals(epoch),"matching recovery proof");
   check(next.restores==1&&!dir.exists(),"abandoned journal was not cleaned");second.close();
  });
  test("UUID Lua workspace without a journal migrates without deleting another UID",()->{
   File root=root();String instance=id(),epoch=id();File own=legacy(root,instance),other=new File(root,"igareck-antidpi-99999-"+id());other.mkdir();
   Host host=new Host();PacketRecovery recovery=manager(root,id(),host);JSONObject proof=recovery.sweep(instance,epoch);
   check(!own.exists()&&other.isDirectory()&&proof.getJSONArray("removed_instances").length()==1&&host.restores==1,"legacy migration scope");recovery.close();
  });
  test("exclusive helper lock rejects a second helper without changing files",()->{
   File root=root();Host host=new Host();PacketRecovery a=manager(root,id(),host);File dir=a.begin(id());PacketRecovery b=manager(root,id(),host);
   rejected(()->b.sweep(null,null));check(dir.exists()&&host.restores==0,"second helper changed resources");a.finish(dir);a.close();b.close();
  });
  test("living old helper identity rejects recovery even without a previous lock",()->{
   File root=root();String instance=id(),epoch=id();Host old=new Host();old.ticks.put(101,"77");PacketRecovery a=manager(root,epoch,old);File dir=a.begin(instance);a.close();
   Host next=new Host();next.pid=102;next.ticks.put(101,"77");PacketRecovery b=manager(root,id(),next);rejected(()->b.sweep(instance,epoch));
   check(dir.exists()&&next.restores==0,"living helper affected");b.close();
  });
  test("reused PID with different start time is not treated as old owner",()->{
   File root=root();String instance=id(),epoch=id();Host old=new Host();old.ticks.put(101,"77");PacketRecovery a=manager(root,epoch,old);File dir=a.begin(instance);a.close();
   Host next=new Host();next.pid=102;next.ticks.put(101,"88");PacketRecovery b=manager(root,id(),next);check(b.sweep(instance,epoch).getBoolean("verified")&&!dir.exists(),"PID reuse blocked verified cleanup");b.close();
  });
  test("remaining owned native process keeps recovery unconfirmed",()->{
   File root=root();String instance=id();File dir=legacy(root,instance);Host host=new Host();host.childAlive=true;PacketRecovery recovery=manager(root,id(),host);
   rejected(()->recovery.sweep(instance,id()));check(dir.exists()&&host.restores==0,"living native was ignored");host.childAlive=false;recovery.sweep(instance,id());check(!dir.exists(),"native exit could not recover");recovery.close();
  });
  test("foreign Ethernet interface is preserved and blocks cleanup",()->{
   File root=root();String instance=id();File dir=legacy(root,instance);Host host=new Host();host.iface="eth0";PacketRecovery recovery=manager(root,id(),host);
   rejected(()->recovery.sweep(instance,id()));check(dir.exists()&&host.restores==0&&host.iface.equals("eth0"),"foreign interface affected");host.iface="";recovery.sweep(instance,id());recovery.close();
  });
  test("parent-death exit is awaited before deleting its workspace",()->{
   File root=root();String instance=id();File dir=legacy(root,instance);Host host=new Host();host.liveCountdown=2;PacketRecovery recovery=manager(root,id(),host);
   recovery.sweep(instance,id());check(host.liveCountdown==0&&!dir.exists(),"recovery did not await exit");recovery.close();
  });
  test("failed visibility restoration keeps durable recovery files for retry",()->{
   File root=root();String instance=id();File dir=legacy(root,instance);Host host=new Host();host.visibilityFails=true;PacketRecovery recovery=manager(root,id(),host);
   rejected(()->recovery.sweep(instance,id()));check(dir.exists(),"failed recovery discarded ownership");host.visibilityFails=false;recovery.sweep(instance,id());check(!dir.exists(),"retry failed");recovery.close();
  });
  test("unexpected files and symlink targets are never removed",()->{
   File root=root();String instance=id();File dir=legacy(root,instance);File extra=new File(dir,"unrelated.txt");Files.writeString(extra.toPath(),"keep");Host host=new Host();PacketRecovery recovery=manager(root,id(),host);
   rejected(()->recovery.sweep(instance,id()));check(host.restores==0&&extra.isFile()&&new File(dir,"zapret-lib.lua").exists(),"unknown file caused a mutation");extra.delete();
   File outside=new File(root,"outside.lua");Files.writeString(outside.toPath(),"private");new File(dir,"zapret-lib.lua").delete();Files.createSymbolicLink(new File(dir,"zapret-lib.lua").toPath(),outside.toPath());
   rejected(()->recovery.sweep(instance,id()));check(Files.readString(outside.toPath()).equals("private"),"symlink followed");recovery.close();
  });
  test("wrong journal epoch and corrupted journal cannot authorize cleanup",()->{
   File root=root();String instance=id(),epoch=id();Host host=new Host();PacketRecovery recovery=manager(root,epoch,host);File dir=recovery.begin(instance);
   rejected(()->recovery.sweep(instance,id()));check(dir.exists(),"wrong epoch accepted");Files.writeString(new File(dir,"owner.json").toPath(),"broken");
   try{recovery.sweep(instance,epoch);throw new AssertionError("corrupt journal accepted");}catch(JSONException expected){}check(dir.exists(),"corrupt journal removed");recovery.close();
  });
  test("symlinked workspace never authorizes visibility changes or deletion",()->{
   File root=root();String instance=id();File outside=Files.createTempDirectory("igareck-foreign-").toFile();
   Files.writeString(new File(outside,"zapret-lib.lua").toPath(),"keep");Files.createSymbolicLink(new File(root,"igareck-antidpi-10364-"+instance).toPath(),outside.toPath());
   Host host=new Host();PacketRecovery recovery=manager(root,id(),host);rejected(()->recovery.sweep(instance,id()));
   check(host.restores==0&&Files.readString(new File(outside,"zapret-lib.lua").toPath()).equals("keep"),"Recovery followed foreign workspace");recovery.close();
  });
  test("empty recovery has no global network side effects",()->{
   Host host=new Host();PacketRecovery recovery=manager(root(),id(),host);JSONObject proof=recovery.sweep(null,null);
   check(proof.getBoolean("verified")&&proof.getJSONArray("removed_instances").length()==0&&host.restores==0,"Clean bootstrap changed network state");recovery.close();
  });
  test("app process restart can sweep orphan without any saved IPC snapshot",()->{
   File root=root();String instance=id(),epoch=id();Host host=new Host();PacketRecovery recovery=manager(root,epoch,host);File dir=recovery.begin(instance);recovery.checkpoint(dir,"testtap9",true);recovery.close();
   Host next=new Host();next.pid=102;PacketRecovery restarted=manager(root,id(),next);JSONObject proof=restarted.sweep(null,null);
   check(proof.getBoolean("verified")&&proof.getJSONArray("removed_instances").length()==1&&!dir.exists(),"stateless orphan recovery");restarted.close();
  });
 }
 public static void main(String[] args)throws Exception{
  if(args.length>0&&args[0].equals("lease-owner")){
   Process verifier=leaseFixture("lease-verifier").redirectOutput(new File(args[1])).start();
   WorkerChannel.write(verifier.getOutputStream(),new JSONObject().put("instance",args[2]).put("epoch",args[3]).put("key",WorkerChannel.token()).toString(),1024);
   Thread.sleep(10000);return;
  }
  if(args.length>0&&args[0].equals("lease-verifier")){
   JSONObject request=StopVerifier.awaitRelease(System.in,System.out);System.out.println("Released "+request.getString("instance"));return;
  }
  run();System.out.println(new JSONObject().put("passed",true).put("count",results.length()).put("tests",results).put("scope","Actual PacketRecovery and verifier Java, real filesystem, file locks and owner process exit with explicit Android resource fixtures."));
 }
}
