package com.igareck.antidpi;
import java.util.concurrent.*;
import java.util.function.*;

final class PacketBridge {
 static volatile boolean alive, fail, savedWorker;
 static volatile int launches, reattachCalls;
 static volatile CountDownLatch entered, release;

 static boolean hasActiveWorker(){return alive;}
 static boolean reattachOnly(){
  reattachCalls++;
  if(savedWorker)alive=true;
  return alive;
 }
 static void restoreAutomatically(BooleanSupplier keep)throws Exception{
  launches++;
  if(entered!=null)entered.countDown();
  while(release!=null&&!release.await(5,TimeUnit.MILLISECONDS)){
   if(!keep.getAsBoolean())throw new CancellationException();
  }
  if(!keep.getAsBoolean())throw new CancellationException();
  if(fail)throw new java.io.IOException("fixture refused");
  alive=true;
 }
}
