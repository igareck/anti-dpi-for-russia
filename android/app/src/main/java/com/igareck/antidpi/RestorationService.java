/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import android.app.*;
import android.content.*;
import android.os.*;
/** One bounded, visible recovery attempt. No permanent foreground polling. */
public final class RestorationService extends Service {
 private static final String CHANNEL="service_restoration";
 private static final int NOTICE=95;
 private final Handler main=new Handler(Looper.getMainLooper());
 private ServiceRestoration.Attempt attempt;
 private int latest;
 private final Runnable timeout=()->{if(attempt!=null)attempt.cancel();ServiceRestoration.scheduleRetry(this,ServiceRestoration.RETRY_DELAY);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();};
 @Override public int onStartCommand(Intent i,int flags,int startId){
  latest=startId;
  NotificationManager nm=getSystemService(NotificationManager.class);
  nm.createNotificationChannel(new NotificationChannel(CHANNEL,UiLanguage.text(this,"Восстановление сетевого модуля"),NotificationManager.IMPORTANCE_LOW));
  PendingIntent open=PendingIntent.getActivity(this,95,new Intent(this,ActivationActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  Notification n=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle(UiLanguage.text(this,"Igareck · восстановление сетевого модуля")).setContentText(UiLanguage.text(this,"Используется сохранённая авторизация. Стратегии автоматически не запускаются.")).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build();
  startForeground(NOTICE,n);
  if(attempt!=null)return START_NOT_STICKY;
  main.postDelayed(timeout,ServiceRestoration.ATTEMPT_LIMIT);
  attempt=ServiceRestoration.begin(this,i==null?"service_restart":i.getStringExtra("origin"),retry->main.post(()->{
   if(retry)ServiceRestoration.scheduleRetry(this,ServiceRestoration.RETRY_DELAY);
   main.removeCallbacks(timeout);stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(latest);
  }));
  if(attempt==null){main.removeCallbacks(timeout);stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(latest);}
  return START_NOT_STICKY;
 }
 @Override public IBinder onBind(Intent i){return null;}
 @Override public void onDestroy(){if(attempt!=null)attempt.cancel();main.removeCallbacks(timeout);super.onDestroy();}
}
