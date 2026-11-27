package com.igareck.antidpi;
import android.system.*;
import java.nio.file.*;
import java.io.*;
import org.json.*;
public final class SessionHarness {
 public static void main(String[] args)throws Exception {
  JSONObject out=new JSONObject();String mode=args[0];
  if(mode.equals("old")) {
   try{Os.setsid();out.put("legacy_rejected",false);}catch(ErrnoException e){out.put("legacy_rejected",true).put("errno",e.errno);}
  }else {
   WorkerSession.detach();WorkerSession.detach();
   out.put("detached_and_idempotent",true);
  }
  out.put("pid",ProcessHandle.current().pid()).put("passed",true);
  if(mode.equals("linger")){
   new BufferedReader(new InputStreamReader(System.in)).readLine();
   WorkerSession.releaseBootstrapStdio();
   for(int fd=0;fd<=2;fd++){
    Path target=Files.readSymbolicLink(Path.of("/proc/self/fd/"+fd));
    if(!target.equals(Path.of("/dev/null")))throw new AssertionError("Inherited descriptor "+fd+" -> "+target);
   }
   out.put("stdio_detached",true);
   Path report=Path.of(System.getProperty("session.report"));
   Path temp=report.resolveSibling(report.getFileName()+".tmp");
   Files.writeString(temp,out.toString());
   Files.move(temp,report,StandardCopyOption.ATOMIC_MOVE);
   Thread.sleep(15000);
  }else System.out.println(out);
 }
}
