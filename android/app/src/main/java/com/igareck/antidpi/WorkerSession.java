/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import java.io.*;

/** Accept an already detached session, otherwise require a real successful setsid. */
final class WorkerSession {
 static void detach()throws Exception{
  int pid=Process.myPid();int[] before=identity();
  if(before[1]!=pid)Os.setsid();
  int[] after=identity();
  if(after[0]!=pid||after[1]!=pid)throw new IllegalStateException("Worker session not detached");
 }
 static void releaseBootstrapStdio()throws Exception{
  FileDescriptor sink=Os.open("/dev/null",OsConstants.O_RDWR|OsConstants.O_CLOEXEC,0);
  try{
   // After the authenticated receipt, all runtime I/O uses the control
   // channel. Drop inherited ADB descriptors before entering the worker loop.
   for(int fd=0;fd<=2;fd++)Os.dup2(sink,fd);
  }finally{Os.close(sink);}
 }
 // getsid/getpgid are hidden Android APIs; self stat exposes the same kernel IDs.
 private static int[] identity()throws IOException{
  byte[] bytes=new byte[8192];int used=0;
  try(InputStream in=new FileInputStream("/proc/self/stat")){
   int count;while(used<bytes.length&&(count=in.read(bytes,used,bytes.length-used))>0)used+=count;
   if(in.read()!=-1)throw new IOException("Oversized process stat");
  }
  String line=new String(bytes,0,used,"US-ASCII");int end=line.lastIndexOf(')');
  if(end<0)throw new IOException("Invalid process stat");
  String[] fields=line.substring(end+1).trim().split(" +");
  try{if(fields.length<4)throw new IOException("Missing process session");return new int[]{Integer.parseInt(fields[2]),Integer.parseInt(fields[3])};}
  catch(NumberFormatException e){throw new IOException("Invalid process session",e);}
 }
 private WorkerSession(){}
}
