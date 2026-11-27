/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import android.app.*;
import android.content.*;
import android.os.*;
import java.util.concurrent.*;

/** User-started foreground pairing, including safe cancellation from every state. */
public final class AdbActivationService extends Service {
 static final String SEARCH="adb.search",PAIR="adb.pair",ACTIVATE="adb.activate",USB="adb.usb",RELEASE="adb.release",CANCEL="adb.cancel",CODE="pairing_code";
 static final String CHANNEL="adb_activation_pairing_v2";private static final int NOTICE=92;
 private final Handler main=new Handler(Looper.getMainLooper());
 private final ExecutorService work=Executors.newSingleThreadExecutor();
 private volatile boolean operation,pairingRequested;private volatile long revision;
 private int lastPairPort,latestStartId;
 private final Runnable timeout=()->{revision++;EmbeddedAdb.cancel();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();};
 private final Runnable refresh=new Runnable(){public void run(){
  int port=pairingRequested&&!EmbeddedAdb.busy()?EmbeddedAdb.pairPort():0;
  boolean alert=port!=0&&port!=lastPairPort;lastPairPort=port;
  getSystemService(NotificationManager.class).notify(NOTICE,notification(alert));main.postDelayed(this,700);
 }};
 static void ensureChannel(Context context){
  NotificationChannel c=new NotificationChannel(CHANNEL,UiLanguage.text(context,"Сопряжение и активация ADB"),NotificationManager.IMPORTANCE_HIGH);
  c.setDescription(UiLanguage.text(context,"Всплывающий ввод кода сопряжения при открытом окне Android"));
  context.getSystemService(NotificationManager.class).createNotificationChannel(c);
 }
 @Override public void onCreate(){super.onCreate();EmbeddedAdb.init(this);ensureChannel(this);}
 private Notification notification(boolean alert){
  PendingIntent open=PendingIntent.getActivity(this,90,new Intent(this,ActivationActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  PendingIntent input=PendingIntent.getForegroundService(this,91,new Intent(this,AdbActivationService.class).setAction(PAIR),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);
  PendingIntent cancel=PendingIntent.getForegroundService(this,93,new Intent(this,AdbActivationService.class).setAction(CANCEL),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  PendingIntent activate=PendingIntent.getForegroundService(this,94,new Intent(this,AdbActivationService.class).setAction(ACTIVATE),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  boolean busy=operation||EmbeddedAdb.busy();String text=EmbeddedAdb.detail();
  if(pairingRequested&&!busy&&EmbeddedAdb.pairPort()!=0)text="Сервис сопряжения найден. Введи код из открытого окна Android.";
  text=UiLanguage.text(this,text);
  Notification.Builder b=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("Igareck · "+UiLanguage.text(this,EmbeddedAdb.status())).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(!alert).setVisibility(Notification.VISIBILITY_PRIVATE);
  if(Build.VERSION.SDK_INT>=31)b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
  if(pairingRequested&&!busy)b.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_send,UiLanguage.text(this,"Ввести код"),input).addRemoteInput(new RemoteInput.Builder(CODE).setLabel(UiLanguage.text(this,"6 цифр из окна сопряжения Android")).build()).build());
  if(EmbeddedAdb.paired()&&!busy)b.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_play,UiLanguage.text(this,"Активировать"),activate).build());
  b.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,UiLanguage.text(this,"Отмена"),cancel).build());return b.build();
 }
 @Override public int onStartCommand(Intent intent,int flags,int startId){
  // Even CANCEL/null starts may arrive via startForegroundService: promote first.
  startForeground(NOTICE,notification(false));
  latestStartId=startId;
  String action=intent==null?CANCEL:intent.getAction();
  if(CANCEL.equals(action)){revision++;pairingRequested=false;EmbeddedAdb.cancel();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY;}
  main.removeCallbacks(timeout);main.postDelayed(timeout,300000);main.removeCallbacks(refresh);main.post(refresh);
  if(RELEASE.equals(action))ServiceRestoration.pause(this);
  else if(PAIR.equals(action)||ACTIVATE.equals(action)||USB.equals(action))ServiceRestoration.manual(this);
  else if(SEARCH.equals(action))ServiceRestoration.cancelCurrent();
  if(SEARCH.equals(action)){if(!operation&&!EmbeddedAdb.busy()){revision++;pairingRequested=true;lastPairPort=0;EmbeddedAdb.search();}return START_NOT_STICKY;}
  if(!PAIR.equals(action)&&!ACTIVATE.equals(action)&&!USB.equals(action)&&!RELEASE.equals(action)){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY;}
  if(operation)return START_NOT_STICKY;operation=true;final long own=++revision;
  Bundle remote=RemoteInput.getResultsFromIntent(intent);CharSequence reply=remote==null?null:remote.getCharSequence(CODE);
  final String code=reply!=null?reply.toString():intent.getStringExtra(CODE);final int port=intent.getIntExtra("pair_port",0);
  intent.removeExtra(CODE);intent.setClipData(null);
  if(ACTIVATE.equals(action)||USB.equals(action)||RELEASE.equals(action))pairingRequested=false;
  work.execute(()->{
   try{
    if(own!=revision)return;
    if(PAIR.equals(action)){EmbeddedAdb.pair(port,code);pairingRequested=false;}
    if(own!=revision)return;
    if(USB.equals(action))PacketBridge.activateUsb();else if(RELEASE.equals(action))PacketBridge.shutdownWorker();else PacketBridge.activate();
    main.post(()->{if(own==revision&&stopSelfResult(latestStartId))stopForeground(STOP_FOREGROUND_REMOVE);});
   }catch(Throwable e){if(own==revision)EmbeddedAdb.failed(PAIR.equals(action)?"pair-or-activate":"activate",e);}
   finally{operation=false;}
  });
  return START_NOT_STICKY;
 }
 @Override public IBinder onBind(Intent intent){return null;}
 @Override public void onDestroy(){main.removeCallbacks(refresh);main.removeCallbacks(timeout);EmbeddedAdb.endSearch();work.shutdown();super.onDestroy();}
}
