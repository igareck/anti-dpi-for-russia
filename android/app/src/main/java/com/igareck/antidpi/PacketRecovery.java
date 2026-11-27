/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.*;
import java.net.NetworkInterface;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Durable ownership journal. A new helper verifies current resources, not old IPC receipts. */
final class PacketRecovery implements AutoCloseable {
 interface Host {
  int pid(); String startTicks(int pid)throws Exception;
  List<Integer> nativePids(String instance)throws Exception;
  List<String> interfaces()throws Exception;
  void restoreVisibility()throws Exception;
 }
 static final class AndroidHost implements Host {
  private final Context context;
  AndroidHost(Context context){this.context=context;}
  public int pid(){return Process.myPid();}
  public String startTicks(int pid)throws Exception {
   File f=new File("/proc/"+pid+"/stat");
   try{
    String line=new String(read(f,8192),StandardCharsets.US_ASCII);
    String[] fields=line.substring(line.lastIndexOf(')')+2).split(" +");
    if(fields.length<20)throw new IOException("Invalid process identity");
    // A zombie has released its TAP and other file descriptors.
    return fields[0].equals("Z")?"":fields[19];
   }catch(FileNotFoundException e){if(!new File("/proc/"+pid).exists())return "";throw e;}
  }
  public List<Integer> nativePids(String instance)throws Exception {
   List<Integer> found=new ArrayList<>();File[] processes=new File("/proc").listFiles();
   if(processes==null)throw new IOException("Cannot enumerate native processes");
   for(File process:processes){
    if(!process.getName().matches("[1-9][0-9]*"))continue;
    try{if(Os.stat(process.getPath()).st_uid!=2000)continue;}
    catch(ErrnoException e){if(e.errno==OsConstants.ENOENT||e.errno==OsConstants.EACCES)continue;throw e;}
    byte[] cmd;
    try{cmd=read(new File(process,"cmdline"),65536);}
    catch(FileNotFoundException e){if(!process.exists())continue;throw e;}
    String[] args=new String(cmd,StandardCharsets.UTF_8).split("\u0000");
    if(args.length==0||!args[0].endsWith("/libnfqws2.so"))continue;
    List<String> values=Arrays.asList(args);
    if(values.contains("--igareck-instance="+instance)&&values.contains("--igareck-socket=igareck-tap-"+instance)
       &&!startTicks(Integer.parseInt(process.getName())).isEmpty())found.add(Integer.parseInt(process.getName()));
   }
   return found;
  }
  public List<String> interfaces()throws Exception {
   List<String> result=new ArrayList<>();Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();
   if(all==null)throw new IOException("Cannot enumerate network interfaces");
   while(all.hasMoreElements()){
    String name=all.nextElement().getName();
    if(name.startsWith("testtap")||name.startsWith("testtun")||name.matches("eth[0-9]+"))result.add(name);
   }
   return result;
  }
  public void restoreVisibility()throws Exception{TapSession.restoreTestInterfaceVisibility(context);}
 }
 private static final Set<String> FILES=new HashSet<>(Arrays.asList("owner.json","owner.json.tmp","zapret-lib.lua","zapret-antidpi.lua","igareck-actual-tls.lua"));
 private final File root;private final int uid;private final String epoch;private final Host host;
 private FileChannel lockChannel;private FileLock lock;
 PacketRecovery(Context context,int uid,String epoch){this(new File("/data/local/tmp"),uid,epoch,new AndroidHost(context));}
 PacketRecovery(File root,int uid,String epoch,Host host){this.root=root;this.uid=uid;this.epoch=epoch;this.host=host;}
 static String canonicalId(String id)throws IOException {
  try{if(id!=null&&UUID.fromString(id).toString().equals(id))return id;}catch(IllegalArgumentException ignored){}
  throw new IOException("Invalid recovery instance");
 }
 private String prefix(){return "igareck-antidpi-"+uid+"-";}
 private void locked()throws IOException {
  if(lock!=null&&lock.isValid())return;
  File file=new File(root,"igareck-antidpi-"+uid+".lock");
  FileChannel channel=FileChannel.open(file.toPath(),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
  FileLock candidate;
  try{candidate=channel.tryLock();}catch(OverlappingFileLockException e){candidate=null;}
  catch(IOException e){channel.close();throw e;}
  if(candidate==null){channel.close();throw new IOException("Предыдущий сетевой модуль ещё работает; повтори после её остановки.");}
  lockChannel=channel;lock=candidate;
 }
 private static byte[] read(File file,int limit)throws IOException {
  try(InputStream input=new FileInputStream(file);ByteArrayOutputStream output=new ByteArrayOutputStream()){
   byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1){if(output.size()+n>limit)throw new IOException("Ownership record exceeds limit");output.write(buffer,0,n);}return output.toByteArray();
  }
 }
 private void directory(File dir)throws IOException {
  if(!dir.getName().startsWith(prefix())||!dir.getCanonicalFile().equals(dir.getAbsoluteFile())||Files.isSymbolicLink(dir.toPath())||!dir.isDirectory())
   throw new IOException("Unexpected native workspace; no files removed");
  canonicalId(dir.getName().substring(prefix().length()));
 }
 private JSONObject readOwner(File dir)throws Exception {
  File owner=new File(dir,"owner.json");
  if(!owner.exists())return null;
  if(Files.isSymbolicLink(owner.toPath()))throw new IOException("Symlinked ownership record");
  JSONObject value=new JSONObject(new String(read(owner,16384),StandardCharsets.UTF_8));
  if(value.getInt("uid")!=uid||!value.getString("instance").equals(dir.getName().substring(prefix().length()))||value.getInt("format")!=1)
   throw new IOException("Ownership record does not match workspace");
  canonicalId(value.getString("epoch"));return value;
 }
 private void writeOwner(File dir,JSONObject value)throws Exception {
  directory(dir);File temporary=new File(dir,"owner.json.tmp"),owner=new File(dir,"owner.json");
  if(Files.isSymbolicLink(temporary.toPath())||Files.isSymbolicLink(owner.toPath()))throw new IOException("Symlinked ownership record");
  try(FileOutputStream out=new FileOutputStream(temporary)){out.write(value.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
  if(!temporary.renameTo(owner))throw new IOException("Cannot commit native ownership record");
 }
 File begin(String instance)throws Exception {
  locked();canonicalId(instance);File dir=new File(root,prefix()+instance);
  if(!dir.mkdir())throw new IOException("Cannot create private native workspace");
  try{
   Os.chmod(dir.getPath(),0700);
   writeOwner(dir,new JSONObject().put("format",1).put("uid",uid).put("instance",instance).put("epoch",epoch)
    .put("service_pid",host.pid()).put("start_ticks",host.startTicks(host.pid())).put("tap","").put("visibility_requested",false));
  }catch(Exception e){File[] files=dir.listFiles();if(files!=null)for(File f:files)if(FILES.contains(f.getName()))f.delete();dir.delete();throw e;}
  return dir;
 }
 void checkpoint(File dir,String tap,boolean visibility)throws Exception {
  JSONObject value=readOwner(dir);if(value==null)throw new IOException("Missing native ownership record");
  if(!value.getString("epoch").equals(epoch))throw new IOException("Foreign native ownership record");
  writeOwner(dir,value.put("tap",tap).put("visibility_requested",visibility));
 }
 private File[] checkedFiles(File dir)throws Exception {
  directory(dir);File[] files=dir.listFiles();if(files==null)throw new IOException("Cannot list native workspace");
  for(File f:files)if(!FILES.contains(f.getName())||Files.isSymbolicLink(f.toPath())||!f.isFile())
   throw new IOException("Unexpected workspace content; no files removed");
  return files;
 }
 private void remove(File dir)throws Exception {
  File[] files=checkedFiles(dir);
  // Keep the durable ownership record until all ordinary files were removed.
  for(File f:files)if(!f.getName().equals("owner.json")&&!f.delete()&&f.exists())throw new IOException("Cannot remove own Lua workspace");
  File owner=new File(dir,"owner.json");if(!owner.delete()&&owner.exists())throw new IOException("Cannot remove ownership record");
  if(!dir.delete()&&dir.exists())throw new IOException("Cannot remove own native workspace");
 }
 void finish(File dir)throws Exception{if(dir!=null&&dir.exists())remove(dir);}
 void verifyReleasedRun(String instance,String expectedEpoch)throws Exception {
  canonicalId(instance);canonicalId(expectedEpoch);
  File dir=new File(root,prefix()+instance);
  Set<String> owned=Collections.singleton(instance);
  // The verifier was armed after the journal was created. Both orderly Stop
  // and orphan recovery remove it only after TAP cleanup. A later run may
  // already hold the lock, so a completed run can be verified read-only.
  if(!workspacePresent(dir)){
   awaitAbsent(owned,n->false);
   return;
  }
  locked();
  if(!workspacePresent(dir)){
   awaitAbsent(owned,n->false);
   return;
  }
  checkedFiles(dir);JSONObject owner=readOwner(dir);
  if(owner!=null){
   if(!expectedEpoch.equals(owner.getString("epoch")))throw new IOException("Recovery epoch does not match ownership record");
   requireReleasedOwner(owner);
  }
  // The TAP may have been created just before the journal checkpoint. Until
  // the test interfaces are gone, their descriptors may still be in use.
  awaitAbsent(owned,n->n.startsWith("testtap")||n.startsWith("testtun"));
  if(owner!=null&&owner.getBoolean("visibility_requested"))host.restoreVisibility();
  remove(dir);
 }
 private boolean workspacePresent(File dir)throws IOException {
  String[] names=root.list();if(names==null)throw new IOException("Cannot enumerate own native workspaces");
  return Arrays.asList(names).contains(dir.getName());
 }
 private void requireReleasedOwner(JSONObject owner)throws Exception {
  int pid=owner.getInt("service_pid");String ticks=owner.getString("start_ticks");
  if(pid!=host.pid()&&!ticks.isEmpty()&&ticks.equals(host.startTicks(pid)))throw new IOException("Предыдущий сетевой модуль ещё работает");
 }
 private void awaitAbsent(Set<String> instances,java.util.function.Predicate<String> blocks)throws Exception {
  long until=SystemClock.elapsedRealtime()+4000;
  for(;;){
   boolean nativeAlive=false;
   for(String id:instances)nativeAlive|=!host.nativePids(id).isEmpty();
   if(!nativeAlive&&host.interfaces().stream().noneMatch(blocks))return;
   if(SystemClock.elapsedRealtime()>=until)throw new IOException("Восстановление отложено: остался собственный nfqws2 или Ethernet/test-интерфейс; чужие ресурсы не изменены.");
   Thread.sleep(100);
  }
 }
 JSONObject sweep(String expectedInstance,String expectedEpoch)throws Exception {
  locked();boolean explicit=expectedInstance!=null;
  if(explicit){canonicalId(expectedInstance);canonicalId(expectedEpoch);}
  File[] files=root.listFiles();if(files==null)throw new IOException("Cannot enumerate own native workspaces");
  List<File> dirs=new ArrayList<>();Map<File,JSONObject> owners=new HashMap<>();
  for(File f:files)if(f.getName().startsWith(prefix())){
   directory(f);checkedFiles(f);String id=f.getName().substring(prefix().length());JSONObject owner=readOwner(f);
   if(owner!=null){
    if(explicit&&id.equals(expectedInstance)&&!owner.getString("epoch").equals(expectedEpoch))throw new IOException("Recovery epoch does not match ownership record");
    requireReleasedOwner(owner);
   }
   dirs.add(f);owners.put(f,owner);
  }
  Set<String> ids=new LinkedHashSet<>();if(explicit)ids.add(expectedInstance);
  for(File f:dirs)ids.add(f.getName().substring(prefix().length()));
  awaitAbsent(ids,n->true);
  boolean visibility=false;
  // If a workspace has no journal, its exact UUID and Lua filenames
  // are still scoped to this application UID. Never stop all Ethernet tethering.
  if(explicit||!dirs.isEmpty()){
   boolean needed=explicit;
   for(File dir:dirs){JSONObject owner=owners.get(dir);needed|=owner==null||owner.optBoolean("visibility_requested");}
   if(needed){host.restoreVisibility();visibility=true;}
  }
  JSONArray removed=new JSONArray();
  for(File dir:dirs){remove(dir);removed.put(dir.getName().substring(prefix().length()));}
  return new JSONObject().put("verified",true).put("previous_instance",explicit?expectedInstance:"")
   .put("previous_service_epoch",explicit?expectedEpoch:"").put("native_absent",true).put("interfaces_absent",true)
   .put("workspace_cleanup_ok",true).put("visibility_restored",visibility).put("removed_instances",removed)
   .put("tethering_cleanup","owned interfaces absent; no global stopTethering call");
 }
 @Override public void close()throws IOException{
  // Closing the channel releases its OS lock. Keep the lock file in place so
  // all helpers continue to contend on the same inode.
  if(lockChannel!=null)lockChannel.close();
  lockChannel=null;lock=null;
 }
}
