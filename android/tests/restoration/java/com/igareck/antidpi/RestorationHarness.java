package com.igareck.antidpi;
import android.content.*;import android.app.job.*;import android.os.*;import java.util.*;import java.util.concurrent.*;import java.util.function.*;import org.json.*;
public final class RestorationHarness {
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static void await(BooleanSupplier ok)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!ok.getAsBoolean()){Handler.drain();if(System.nanoTime()>until)throw new AssertionError("fixture wait timed out");Thread.sleep(3);}Handler.drain();}
 static boolean attempt(Context c)throws Exception{CountDownLatch done=new CountDownLatch(1);boolean[] retry={false};ServiceRestoration.Attempt a=ServiceRestoration.begin(c,"fixture",r->{retry[0]=r;done.countDown();});check(a!=null,"Attempt not accepted");check(done.await(3,TimeUnit.SECONDS),"Attempt did not finish");return retry[0];}
 public static void main(String[] args)throws Exception{
 Context c=new Context();Context.permission=true;ServiceRestoration.initialize(c);
 check(!ServiceRestoration.eligible(c)&&Context.jobs.jobs.isEmpty(),"Fresh install requested privileged recovery");ServiceRestoration.boot(c,"boot_completed");check(Context.starts.isEmpty(),"Unconfigured boot started a foreground service");
 EmbeddedAdb.paired=true;Context.cm.wifi=true;EmbeddedAdb.wireless=0;ServiceRestoration.initialize(c);
 check(ServiceRestoration.eligible(c),"Existing pairing and settings grant were not migrated");JobInfo watch=Context.jobs.getPendingJob(ServiceRestoration.WATCH_JOB);
 check(watch!=null&&watch.persisted&&watch.interval>=900000,"Recovery monitor not reboot-persistent/bounded");
 check(watch.network.transports.contains(1)&&!watch.network.capabilities.contains(12),"Recovery incorrectly requires Internet/validated Wi-Fi");
 int scheduled=Context.jobs.schedules;ServiceRestoration.reconcile(c);check(Context.jobs.schedules==scheduled,"Repeated activity refresh replaced scheduler jobs");
 check(ServiceRestoration.launchReady(c),"Granted auto-enable could not recover on Wi-Fi");AutoActivation.configure(c,false);check(!ServiceRestoration.launchReady(c),"Disabled auto-enable changed debugging");EmbeddedAdb.wireless=1;check(ServiceRestoration.launchReady(c),"Already-enabled debugging was incorrectly blocked");AutoActivation.configure(c,true);EmbeddedAdb.wireless=0;
 ServiceRestoration.boot(c,"boot_completed");check(Context.starts.size()==1,"Ready boot did not request a bounded foreground attempt");
 JobInfo retry=Context.jobs.getPendingJob(ServiceRestoration.RETRY_JOB);check(retry!=null&&retry.persisted&&retry.backoff>=60000&&retry.policy==JobInfo.BACKOFF_POLICY_EXPONENTIAL,"Deferred job lacks persistent backoff");
 Context.starts.clear();ServiceRestoration.boot(c,"package_replaced");check(Context.starts.isEmpty()&&Context.jobs.getPendingJob(ServiceRestoration.RETRY_JOB).latency==45000,"Package update reused an old APK helper without deferred recheck");
 Context.cm.wifi=false;Context.starts.clear();ServiceRestoration.boot(c,"boot_completed");check(Context.starts.isEmpty(),"Only-SIM boot started unrequested hotspot");check(!attempt(c)&&PacketBridge.launches==0,"Missing Wi-Fi initiated ADB instead of waiting");
 Context.jobs.cancel(ServiceRestoration.RETRY_JOB);Context.cm.available();check(Context.jobs.getPendingJob(ServiceRestoration.RETRY_JOB)!=null,"Physical Wi-Fi return did not schedule recovery");
 check(!attempt(c)&&PacketBridge.alive&&PacketBridge.launches==1,"Saved authorization did not restore helper");check(Context.power.held==0&&Context.power.timeout==125000,"Recovery leaked wake lock/unbounded timeout");
 Context.cm.wifi=false;int launched=PacketBridge.launches;check(!attempt(c)&&PacketBridge.launches==launched,"Live helper was restarted when Wi-Fi disappeared");
 PacketBridge.alive=false;AutoActivation.configure(c,true);check(!ServiceRestoration.launchReady(c),"Restoration attempted launch without Wi-Fi");EmbeddedAdb.wireless=1;check(!ServiceRestoration.launchReady(c),"Stale wireless flag bypassed station requirement");EmbeddedAdb.wireless=0;Context.cm.wifi=true;
 Context.jobs.cancel(ServiceRestoration.RETRY_JOB);
 ServiceRestoration.workerLinkLost(c);
 check(Context.starts.size()==1&&Context.starts.get(0).getStringExtra("origin").equals("worker_link_lost"),"Lost worker did not trigger Wi-Fi restoration");
 check(Context.jobs.getPendingJob(ServiceRestoration.RETRY_JOB).latency==1500,"Missing bounded retry for lost worker");
 Context.starts.clear();
 Context.jobs.cancel(ServiceRestoration.RETRY_JOB);
 Context.cm.wifi=false;
 int attachBefore=PacketBridge.reattachCalls;
 ServiceRestoration.workerLinkLost(c);
 await(()->PacketBridge.reattachCalls>attachBefore);
 check(!PacketBridge.alive&&PacketBridge.launches==launched&&Context.starts.isEmpty(),"Offline loss attempted fresh activation");
 check(Context.jobs.getPendingJob(ServiceRestoration.RETRY_JOB)==null,"Offline loss scheduled fresh ADB");
 PacketBridge.savedWorker=true;
 ServiceRestoration.workerLinkLost(c);
 await(()->PacketBridge.alive);
 check(PacketBridge.launches==launched,"Offline reattach relaunched a surviving worker");
 PacketBridge.alive=false;PacketBridge.savedWorker=false;Context.cm.wifi=true;
 PacketBridge.entered=new CountDownLatch(1);PacketBridge.release=new CountDownLatch(1);CountDownLatch done=new CountDownLatch(1);ServiceRestoration.Attempt running=ServiceRestoration.begin(c,"held",r->done.countDown());check(PacketBridge.entered.await(2,TimeUnit.SECONDS),"Recovery did not enter bootstrap");
 check(ServiceRestoration.begin(c,"duplicate",r->{})==null,"Concurrent boot/job opened another bootstrap");ServiceRestoration.pause(c);check(done.await(2,TimeUnit.SECONDS),"Explicit pause did not cancel own bootstrap");check(!PacketBridge.alive&&Context.jobs.jobs.isEmpty(),"Pause retained jobs or completed a cancelled activation");
 ServiceRestoration.boot(c,"boot_completed");ServiceRestoration.visible(c);check(Context.starts.isEmpty(),"Paused restoration resumed on boot/app opening");check(AutoActivation.restorePaused(new Context()),"Pause did not survive application recreation");
 ServiceRestoration.manual(c);check(!AutoActivation.restorePaused(c)&&ServiceRestoration.eligible(c),"Explicit launch did not clear pause");PacketBridge.release=null;PacketBridge.entered=null;
 PacketBridge.fail=true;check(attempt(c),"Bootstrap failure did not request backoff");PacketBridge.fail=false;
 AutoActivation.setRestoreEnabled(c,false);ServiceRestoration.optionsChanged(c);check(Context.jobs.jobs.isEmpty()&&!ServiceRestoration.eligible(c),"Disabled restoration retained jobs");AutoActivation.setRestoreEnabled(c,true);ServiceRestoration.optionsChanged(c);
 PacketBridge.alive=false;Context.denyForeground=true;Context.jobs.cancel(ServiceRestoration.RETRY_JOB);ServiceRestoration.visible(c);check(Context.jobs.getPendingJob(ServiceRestoration.RETRY_JOB)!=null,"Foreground-start rejection did not fall back to JobScheduler");Context.denyForeground=false;
 Context.permission=false;EmbeddedAdb.wireless=1;check(ServiceRestoration.eligible(c)&&ServiceRestoration.launchReady(c),"Saved pairing could not use already-enabled debugging without a settings grant");Context.permission=true;EmbeddedAdb.wireless=0;
 EmbeddedAdb.busy=true;launched=PacketBridge.launches;check(attempt(c)&&PacketBridge.launches==launched,"Manual pairing was interrupted by automatic recovery");EmbeddedAdb.busy=false;
 PacketBridge.entered=new CountDownLatch(1);PacketBridge.release=new CountDownLatch(1);CountDownLatch expired=new CountDownLatch(1);ServiceRestoration.Attempt bound=ServiceRestoration.begin(c,"deadline",r->expired.countDown());check(PacketBridge.entered.await(2,TimeUnit.SECONDS),"Deadline fixture did not enter");SystemClock.offset+=120001;check(expired.await(2,TimeUnit.SECONDS),"Recovery deadline was not enforced");SystemClock.offset=0;check(Context.power.held==0,"Cancelled deadline retained wake lock");PacketBridge.entered=null;PacketBridge.release=null;
 RestorationJobService job=new RestorationJobService();JobParameters p=new JobParameters(ServiceRestoration.RETRY_JOB);check(job.onStartJob(p),"Scheduled recovery was not asynchronous");await(()->!job.finished.isEmpty());check(PacketBridge.alive&&job.finished.get(0).endsWith(":false"),"Successful recovery job did not finish without retry");
 PacketBridge.alive=false;PacketBridge.entered=new CountDownLatch(1);PacketBridge.release=new CountDownLatch(1);JobParameters old=new JobParameters(ServiceRestoration.RETRY_JOB);check(job.onStartJob(old),"Stoppable job did not begin");check(PacketBridge.entered.await(2,TimeUnit.SECONDS),"Job did not reach cancellable bootstrap");check(job.onStopJob(old),"OS interruption did not retain retry intent");await(()->!ServiceRestoration.snapshot(c).contains("\"attempt_running\":true"));check(job.finished.size()==1,"Stopped job received stale completion");PacketBridge.entered=null;PacketBridge.release=null;
 RestorationService fg=new RestorationService();int mode=fg.onStartCommand(new Intent(c,RestorationService.class).putExtra("origin","boot"),0,4);check(mode==2&&fg.calls.contains("foreground"),"Boot recovery was not promptly promoted/not-sticky");await(()->fg.calls.contains("stop-result"));check(!Handler.delayed.values().contains(120000L),"Completed foreground attempt retained timeout");fg.onDestroy();
 PacketBridge.alive=false;
 int beforeUsb=PacketBridge.launches;
 ServiceRestoration.usbConfigurationChanged();
 CountDownLatch usbReady=new CountDownLatch(1);
 check(ServiceRestoration.begin(c,"fixture_usb",r->usbReady.countDown())!=null,"USB fixture did not start");
 await(()->ServiceRestoration.describe(c).contains("завершение настройки USB"));
 check(PacketBridge.launches==beforeUsb&&ServiceRestoration.usbSettling(),"Started ADB during USB reconfiguration");
 Context.cm.wifi=false;
 check(usbReady.await(2,TimeUnit.SECONDS)&&PacketBridge.launches==beforeUsb,"Wi-Fi loss during USB settling started ADB");
 Context.cm.wifi=true;
 ServiceRestoration.usbConfigurationChanged();
 CountDownLatch usbStable=new CountDownLatch(1);
 check(ServiceRestoration.begin(c,"fixture_usb_stable",r->usbStable.countDown())!=null,"Stable USB fixture did not start");
 await(()->ServiceRestoration.describe(c).contains("завершение настройки USB"));
 SystemClock.offset+=7000;
 ServiceRestoration.usbConfigurationChanged();
 SystemClock.offset+=6000;
 check(ServiceRestoration.usbSettling()&&PacketBridge.launches==beforeUsb,"A later USB event failed to reset the quiet window");
 SystemClock.offset+=7000;
 check(usbStable.await(2,TimeUnit.SECONDS)&&PacketBridge.launches==beforeUsb+1&&PacketBridge.alive,"Stable USB did not restore through saved Wi-Fi pairing");
 ServiceRestoration.pause(c);int starts=Context.starts.size();new RestorationReceiver().onReceive(c,new Intent(c,RestorationReceiver.class).setAction("unrelated"));check(Context.starts.size()==starts,"Unrelated broadcast triggered activation");
 JSONObject snap=new JSONObject(ServiceRestoration.snapshot(c));check(!snap.optBoolean("restores_strategies")&&snap.optBoolean("paused"),"Recovery diagnostics imply strategy auto-start or lose pause");check(AppLog.lines.stream().noneMatch(s->s.contains("capability")),"Recovery logged a private capability");
 System.out.println(new JSONObject().put("passed",true).put("checks",checks).put("production_recovery_coordinator",true).put("production_job_foreground_receivers",true).put("android_scheduler_radio_permissions","explicit fixtures").put("physical_samsung_tested",false));
 }
}
