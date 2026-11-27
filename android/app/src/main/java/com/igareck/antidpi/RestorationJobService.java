/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import android.app.job.*;
import android.os.*;
import java.util.*;
/** Scheduler owns the wakeup; this job never starts a foreground service. */
public final class RestorationJobService extends JobService {
 private final Handler main=new Handler(Looper.getMainLooper());
 private static final class Execution {ServiceRestoration.Attempt attempt;}
 private final Map<Integer,Execution> owned=new HashMap<>();
 @Override public boolean onStartJob(JobParameters p){
  if(!ServiceRestoration.eligible(this))return false;
  Execution execution=new Execution();
  ServiceRestoration.Attempt a=ServiceRestoration.begin(this,p.getJobId()==ServiceRestoration.RETRY_JOB?"network_or_retry_job":"periodic_job",retry->main.post(()->{
   if(owned.get(p.getJobId())!=execution)return;
   owned.remove(p.getJobId());
   if(!execution.attempt.cancelled)jobFinished(p,retry&&ServiceRestoration.eligible(this));
  }));
  if(a==null)return false;
  execution.attempt=a;owned.put(p.getJobId(),execution);return true;
 }
 @Override public boolean onStopJob(JobParameters p){Execution e=owned.remove(p.getJobId());if(e!=null)e.attempt.cancel();return ServiceRestoration.eligible(this);}
 @Override public void onDestroy(){for(Execution e:owned.values())e.attempt.cancel();owned.clear();super.onDestroy();}
}
