package com.igareck.antidpi;
import android.content.Context;
import rikka.shizuku.Shizuku;
/** Explicit ADB/Android bootstrap boundary for existing resource race fixtures. */
final class EmbeddedAdb {
 static void init(Context context){}
 static Context appContext(){return new Context();}
 static String launchWorker(boolean offline,java.util.function.BooleanSupplier keep)throws Exception{if(!keep.getAsBoolean())throw new java.util.concurrent.CancellationException();return launchWorker();}
 static boolean canActivate(){return Shizuku.available;}
 static boolean debuggingOff;
 static boolean wifiDisconnected;
 static final class ActivationRequiredException extends java.io.IOException {
  ActivationRequiredException(){super("Сопряжение сохранено. Для запуска подключись к Wi-Fi или используй «Запуск через USB» с компьютера.");}
 }
 static boolean backgroundLaunchAllowed(){return Shizuku.available&&!debuggingOff;}
 static void activated(){}
 static void deactivated(){}
 static String launchUsbWorker()throws Exception{return launchWorker();}
 static void failed(String op,Throwable e){}
 static String detail(){return "Embedded ADB fixture unavailable";}
 static String launchWorker()throws Exception{
  Shizuku.binds++;
  if(wifiDisconnected)throw new ActivationRequiredException();
  if(Shizuku.failNext){Shizuku.failNext=false;throw new java.io.IOException("Injected activation failure");}
  if(Shizuku.suppressNext){Shizuku.suppressNext=false;throw new java.io.IOException("Injected activation timeout");}
  return IWorkerLauncher.Stub.asInterface(Shizuku.target).launch(WorkerChannel.token());
 }
}
