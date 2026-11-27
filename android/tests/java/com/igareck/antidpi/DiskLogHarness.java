package com.igareck.antidpi;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

public final class DiskLogHarness {
    public static void main(String[] args)throws Exception{
        File base=new File(args[0]);base.mkdirs();
        File oldDirectory=new File(base,"logs");oldDirectory.mkdirs();
        for(int i=0;i<4;i++){
            File legacy=new File(oldDirectory,"session_20261106_20173"+i+"_839.log");
            Files.write(legacy.toPath(),("DATE_BASED_"+i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            legacy.setLastModified(System.currentTimeMillis()-86400000L);
        }
        try(DiskLog log=new DiskLog(new File(base,"logs"))){
            String large=String.join("",Collections.nCopies(200,"0123456789"));
            for(int batch=0;batch<7;batch++){
                for(int i=0;i<1000;i++)log.append("B"+batch+" L"+i+" "+large);
                File flush=log.snapshot(base);DiskLog.removeSnapshot(flush);
            }
            File snapshot=log.snapshot(base);
            File[] entries=snapshot.listFiles();
            if(entries==null||entries.length!=5)throw new AssertionError("Rotation count");
            Map<String,Long> sizes=new HashMap<>();
            for(File entry:entries){
                if(entry.getName().startsWith("session_20261106"))throw new AssertionError("Rotation retained stale date-based logs");
                if(entry.length()>2*1024*1024)throw new AssertionError("Rotation byte limit");sizes.put(entry.getName(),entry.length());
            }
            CountDownLatch destinationBlocked=new CountDownLatch(1), releaseDestination=new CountDownLatch(1);
            FutureTask<Void> export=new FutureTask<>(()->{
                try(ZipOutputStream zip=new ZipOutputStream(new OutputStream(){
                    public void write(int b)throws IOException{block();}
                    public void write(byte[] b,int off,int len)throws IOException{block();}
                    private void block()throws IOException{
                        destinationBlocked.countDown();
                        try{if(!releaseDestination.await(8,TimeUnit.SECONDS))throw new IOException("Test gate timeout");}
                        catch(InterruptedException e){throw new IOException(e);}
                    }
                })){
                    for(File f:entries){zip.putNextEntry(new ZipEntry(f.getName()));Files.copy(f.toPath(),zip);zip.closeEntry();}
                }return null;
            });
            new Thread(export,"blocked-export-fixture").start();
            if(!destinationBlocked.await(3,TimeUnit.SECONDS))throw new AssertionError("Destination did not block");
            long begin=System.nanoTime();
            for(int i=0;i<20000;i++)log.append("WHILE_EXTERNAL_ZIP_BLOCKED "+i);
            long elapsed=(System.nanoTime()-begin)/1000000;
            releaseDestination.countDown();export.get(8,TimeUnit.SECONDS);
            if(elapsed>3000)throw new AssertionError("Native-log producer blocked for "+elapsed+" ms");
            for(File f:entries)if(f.length()!=sizes.get(f.getName()))throw new AssertionError("Snapshot mutated during export");
            DiskLog.removeSnapshot(snapshot);
            log.append("FINAL_SENTINEL");File last=log.snapshot(base);
            boolean found=false;for(File f:last.listFiles())if(new String(Files.readAllBytes(f.toPath()),java.nio.charset.StandardCharsets.UTF_8).contains("FINAL_SENTINEL"))found=true;
            DiskLog.removeSnapshot(last);
            if(!found)throw new AssertionError("Writer stopped during blocked export");
            System.out.println("DISK_LOG_PASS producer_ms="+elapsed+"; dropped_lines="+log.droppedLines()+"; error="+log.error());
        }
    }
}
