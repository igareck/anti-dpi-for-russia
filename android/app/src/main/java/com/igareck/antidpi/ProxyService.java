/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** One foreground owner for the public nfqws2/TAP backend. */
public final class ProxyService extends Service {
    public static final String START="com.igareck.antidpi.START",STOP="com.igareck.antidpi.STOP",YOUTUBE_START="com.igareck.antidpi.YOUTUBE_START",YOUTUBE_STOP="com.igareck.antidpi.YOUTUBE_STOP",DISCORD_START="com.igareck.antidpi.DISCORD_START",DISCORD_STOP="com.igareck.antidpi.DISCORD_STOP";
    public static final String SCAN_START="com.igareck.antidpi.SCAN_START",SCAN_STOP="com.igareck.antidpi.SCAN_STOP";
    private static final String CHANNEL="local_proxy";
    static final String ACTIVATION_REQUIRED="Требуется активация сетевого модуля";
    private static final int NOTICE=1;
    private static final AtomicLong OWNERS=new AtomicLong();
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private static volatile long currentOwner;
    private static volatile ProxyService instance;
    public static volatile String state="Остановлен",activeId="—",activeBackend="nfqws2",failure="";
    public static volatile int activePort=1080;
    public static volatile boolean running,activeUdp;
    public static volatile String activeQuicId="Q1",activeQuicHash="",activeDiscordId="D0",activeDiscordHash="";
    public static volatile String activeInstance="none",activeRecipeHash="";
    public static volatile int activeRecipeNumber;
    private final long owner=OWNERS.incrementAndGet();
    private volatile boolean destroyed,packetOwned,stopRequested,runRequested,activeVerbose;
    private volatile long generation;
    private volatile int latestStartId;
    private PowerManager.WakeLock wakeLock;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private String lastNetwork="";
    private volatile String lastNoticeText="";
    private boolean owns(){return !destroyed&&currentOwner==owner;}
    private boolean wanted(long token){return owns()&&generation==token;}

    @Override public void onCreate(){
        super.onCreate();currentOwner=owner;instance=this;
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        ensureChannel(nm);
        wakeLock=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"IgareckAntiDPI:Proxy");
        wakeLock.setReferenceCounted(false);
        connectivity=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        networkCallback=new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(Network n){AppLog.line("NETWORK","Default network available: "+n);}
            @Override public void onLost(Network n){AppLog.line("NETWORK","Default network lost; reconnect existing VPN TCP connections");}
            @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities caps){
                String type=caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?"Wi-Fi":caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?"mobile":caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)?"VPN":"other";
                String value=type+"; validated="+caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                if(!value.equals(lastNetwork)){lastNetwork=value;AppLog.line("NETWORK",value);
                    if(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))AppLog.line("ROUTING","Exclude Igareck AntiDPI from the VPN client.");}
            }
        };
        try{connectivity.registerDefaultNetworkCallback(networkCallback);}catch(Exception e){AppLog.error("Network callback",e);}
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        latestStartId=startId;
        String action=intent==null?STOP:intent.getAction();
        boolean scanStart=SCAN_START.equals(action)||YOUTUBE_START.equals(action)||DISCORD_START.equals(action);
        boolean scanStop=SCAN_STOP.equals(action)||YOUTUBE_STOP.equals(action)||DISCORD_STOP.equals(action);
        if(scanStart||scanStop){
            WebTarget target=scanTarget(intent);
            if(target==null){if(!running&&!TcpStrategyScan.busy())stopSelfResult(startId);return START_NOT_STICKY;}
            TcpStrategyScan scan=TcpStrategyScan.forTarget(target);
            if(scanStop){scan.cancelWithTransport(PacketBridge::cancelPending);return START_NOT_STICKY;}
            if(TcpStrategyScan.busy())return START_NOT_STICKY;
            final int[] numbers;final int parallel=intent.getIntExtra("parallel",3);
            try{numbers=TcpIds.range(intent.getStringExtra("range"));if(parallel<1||parallel>5)throw new IllegalArgumentException("Параллельность: 1–5");}
            catch(Exception e){failure=e.getMessage();if(!running)stopSelfResult(startId);return START_NOT_STICKY;}
            final boolean resume=runRequested&&!stopRequested;
            final String priorId=activeId,priorQuic=activeQuicId,priorDiscord=activeDiscordId;
            final int priorPort=activePort;final boolean priorUdp=activeUdp,priorVerbose=activeVerbose;
            if(!scan.reserve())return START_NOT_STICKY;
            ServiceRestoration.manual(this);
            final long token=++generation;runRequested=false;stopRequested=false;state="Проверяется "+scan.target.label;failure="";
            startForeground(NOTICE,notification("Проверка TCP-стратегий для "+scan.target.label));
            WORKER.execute(()->scanWebsite(scan,numbers,parallel,token,startId,resume,priorId,priorPort,priorVerbose,priorUdp,priorQuic,priorDiscord));
            return START_NOT_STICKY;
        }
        if(TcpStrategyScan.busy()&&!STOP.equals(action))return START_NOT_STICKY;
        boolean stopping=STOP.equals(action);
        if(!stopping&&!START.equals(action)){if(!running)stopSelfResult(startId);return START_NOT_STICKY;}
        // Repeated Stop acknowledges the same intent; do not spawn another retry
        // loop or replace the generation while cleanup is already being checked.
        if(stopping&&stopRequested)return START_NOT_STICKY;
        long token=++generation;
        if(stopping){
            runRequested=false;stopRequested=true;if(TcpStrategyScan.busy())TcpStrategyScan.cancelActive();PacketBridge.cancelPending();state="Останавливается";
            startForeground(NOTICE,notification("Остановка собственного движка…"));
            WORKER.execute(()->stopForUser(token,startId));
        }else{
            ServiceRestoration.manual(this);
            runRequested=true;stopRequested=false;state="Запускается";failure="";
            startForeground(NOTICE,notification("Запуск локального прокси…"));
            String id=intent.getStringExtra("profile");int port=intent.getIntExtra("port",1080);boolean verbose=intent.getBooleanExtra("verbose",false);
            boolean quicEnabled=intent.getBooleanExtra("udp",false);
            String selectedQuic=intent.getStringExtra("quic")==null?"Q1":intent.getStringExtra("quic");
            String selectedDiscord=intent.getStringExtra("discord")==null?"D0":intent.getStringExtra("discord");
            boolean discordEnabled=ProxyOptions.discordEnabled(quicEnabled,intent.getBooleanExtra("discord_enabled",quicEnabled&&!"D0".equals(selectedDiscord)));
            final boolean udp=quicEnabled;
            final String quic=ProxyOptions.quicId(quicEnabled,discordEnabled,selectedQuic);
            final String discord=discordEnabled?selectedDiscord:"D0";
            WORKER.execute(()->startProxy(id,port,verbose,token,startId,false,udp,quic,discord));
        }
        return START_NOT_STICKY;
    }
    private static WebTarget scanTarget(Intent intent){
        String action=intent.getAction();
        if(YOUTUBE_START.equals(action)||YOUTUBE_STOP.equals(action))return WebTarget.YOUTUBE;
        if(DISCORD_START.equals(action)||DISCORD_STOP.equals(action))return WebTarget.DISCORD;
        return WebTarget.fromKey(intent.getStringExtra("target"));
    }
    private void scanWebsite(TcpStrategyScan scan,int[] numbers,int parallel,long token,int startId,boolean resume,String id,int port,boolean verbose,boolean udp,String quic,String discord){
        try{
            if(!wanted(token))return;
            if(!PacketBridge.permitted())throw new IOException("Активируй сетевой модуль для проверки "+scan.target.label+".");
            stopChild();
            scan.run(numbers,parallel,new TcpStrategyScan.Transport(){
                public JSONObject start(int[] batch)throws Exception{
                    if(!wanted(token)||stopRequested)throw new IOException("Проверка отменена");
                    packetOwned=true;if(!wakeLock.isHeld())wakeLock.acquire();
                    JSONObject value=PacketBridge.startYoutube(batch,()->wanted(token)&&!stopRequested&&!scan.cancelledByUser());
                    notifyState(scan.progress);return value;
                }
                public void stop(){stopChild();}
                public boolean keepsTapBetweenBatches(){return true;}
            },()->wanted(token)&&!stopRequested);
        }catch(Exception e){
            if(wanted(token)){scan.error=e.getMessage();scan.progress="Проверка прервана: "+e.getMessage();scan.finished=true;AppLog.error(scan.target.label+" service",e);}
        }finally{
            try{
                if(wanted(token)&&!stopRequested){
                    if(resume){
                        activeId=id;activePort=port;activeVerbose=verbose;activeUdp=udp;activeQuicId=quic;activeDiscordId=discord;
                        runRequested=true;state="Восстанавливается";
                        // Existing recovery logic waits for embedded ADB activation and verifies
                        // resource cleanup before restoring the saved T/Q/D run.
                        restorePacket(token,activeInstance,startId);
                    }else{runRequested=false;stopForScan(startId);}
                }
            }finally{scan.release();}
        }
    }
    private void stopForScan(int startId){
        try{stopChild();confirmedStop(startId);}
        catch(RuntimeException e){stopRequested=true;stopForUser(generation,startId);}
    }
    private void startProxy(String id,int port,boolean verbose,long token,int startId){
        startProxy(id,port,verbose,token,startId,false,false,"Q1","D0");
    }
    private void startProxy(String id,int port,boolean verbose,long token,int startId,boolean restoring,boolean udp,String quic,String discord){
        boolean replacing=false;
        try{
            if(!wanted(token))return;Profiles.validate(port);Profiles.Profile profile=Profiles.get(this,id);QuicProfiles.hash(quic);DiscordProfiles.hash(discord);
            if(Build.VERSION.SDK_INT<30)throw new IOException("Для nfqws2/TAP нужен Android 11 или новее.");
            if(!PacketBridge.permitted()){
                if(packetOwned&&running){state="Работает";failure="Для смены стратегии активируй сетевой модуль. Текущий движок можно остановить.";watchPacket(token,activeInstance,startId);return;}
                throw new IOException("Открой «Активация сетевого модуля» и активируй сетевой модуль.");
            }
            if(!new File(getApplicationInfo().nativeLibraryDir,"libnfqws2.so").canExecute())throw new IOException("В APK нет nfqws2 для текущего ABI.");
            if(!wanted(token))return;replacing=true;
            packetOwned=true;
            AppLog.line("START","profile="+profile.id+"; backend=nfqws2; port="+port+"; verbose="+verbose+"; udp="+udp+"; quic="+quic+"; discord="+discord);
            JSONObject remote=PacketBridge.start(profile.id,port,verbose,udp,quic,discord,()->wanted(token));
            if(!wanted(token)){stopChild();return;}
            Diagnostics.handshake(port);activeId=profile.id;activeBackend="nfqws2";activePort=port;
            activeInstance=remote.getString("instance");activeRecipeNumber=profile.recipeNumber;activeRecipeHash=profile.recipeHash;
            activeVerbose=verbose;activeUdp=udp;activeQuicId=quic;activeQuicHash=udp?QuicProfiles.hash(quic):"";activeDiscordId=discord;activeDiscordHash=udp?DiscordProfiles.hash(discord):"";
            Diagnostics.settingsApplied(activeInstance);running=true;failure="";state="Работает";
            if(!wakeLock.isHeld())wakeLock.acquire();notifyState(activeId+" · 127.0.0.1:"+port);
            AppLog.line("READY","profile="+id+"; backend=nfqws2; instance="+activeInstance+"; udp="+udp+"; quic="+quic+"; discord="+discord+"; ADB UID 2000; TAP; own SOCKS confirmed");
            watchPacket(token,activeInstance,startId);
        }catch(Exception e){if(wanted(token)){
            if(restoring&&runRequested&&!stopRequested&&!PacketBridge.permitted()){
                running=false;state="Восстановление ожидает активации";failure="Связь снова потеряна. Запрос работы сохранён; Стоп отменит его.";
                AppLog.line("PACKET_RESTORE_WAIT","Broker lost during restore start; resource ownership retained: "+e.getClass().getSimpleName());
            }
            else if(!restoring&&e instanceof PacketBridge.CleanupPendingException&&restoreWanted(token)){
                recoverAndStart(token,activeInstance,startId,id,port,verbose,udp,quic,discord);
            }
            else if(!replacing&&packetOwned&&running){state="Работает";failure=e.getMessage();watchPacket(token,activeInstance,startId);}
            else fail(e.getMessage(),e,startId);
        }}
    }
    private boolean watchesPacket(long token,String owned){return wanted(token)&&packetOwned&&runRequested&&!stopRequested&&owned.equals(activeInstance);}
    private void watchPacket(long token,String owned,int startId){
        Thread monitor=new Thread(()->{
            String previous="";long unavailableSince=-1;
            while(watchesPacket(token,owned)){
                try{
                    Thread.sleep(700);if(!watchesPacket(token,owned))return;
                    boolean prolonged=unavailableSince>=0&&SystemClock.elapsedRealtime()-unavailableSince>=6000;
                    JSONObject remote=PacketBridge.refresh(prolonged,()->watchesPacket(token,owned));if(!watchesPacket(token,owned))return;
                    unavailableSince=-1;
                    // A new empty helper has no old native child. Verify the
                    // previous resource journal before restoring the user's
                    // still-active request; a new Binder alone proves nothing.
                    if("STOPPED".equals(remote.optString("state"))&&!remote.optBoolean("native_alive")
                        &&(remote.optString("instance").isEmpty()||owned.equals(remote.optString("instance")))){
                        WORKER.execute(()->{if(watchesPacket(token,owned))restorePacket(token,owned,startId);});return;
                    }
                    if(!owned.equals(remote.optString("instance"))||!"RUNNING".equals(remote.optString("state"))||!remote.optBoolean("native_alive")){
                        WORKER.execute(()->{if(watchesPacket(token,owned))fail(remote.optString("error","Пакетный движок завершился"),null,startId);});return;
                    }
                    if(!running||!failure.isEmpty())WORKER.execute(()->{
                        if(watchesPacket(token,owned)){running=true;state="Работает";failure="";notifyState(activeId+" · 127.0.0.1:"+activePort);}
                    });
                    previous="";
                }catch(InterruptedException e){Thread.currentThread().interrupt();return;}
                catch(Exception e){
                    if(!watchesPacket(token,owned))return;
                    String message=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
                    if(!message.equals(previous)){previous=message;AppLog.line("PACKET_MONITOR","Connection unavailable; will recheck: "+message);}
                    if(unavailableSince<0)unavailableSince=SystemClock.elapsedRealtime();
                    if(SystemClock.elapsedRealtime()-unavailableSince<3500)continue;
                    WORKER.execute(()->{if(watchesPacket(token,owned)){running=false;state="Нет связи с движком";failure="Проверяется связь. После возвращения сетевого модуля статус обновится автоматически.";notifyState("Ожидается связь с собственным движком");}});
                }
            }
        },"packet-monitor");monitor.setDaemon(true);monitor.start();
    }
    private boolean restoreWanted(long token){return wanted(token)&&runRequested&&!stopRequested;}
    private void restorePacket(long token,String oldInstance,int startId){
        if(!restoreWanted(token)||!oldInstance.equals(activeInstance))return;
        recoverAndStart(token,oldInstance,startId,activeId,activePort,activeVerbose,activeUdp,activeQuicId,activeDiscordId);
    }
    private void recoverAndStart(long token,String oldInstance,int startId,String profile,int port,boolean verbose,boolean udp,String quic,String discord){
        running=false;state="Восстанавливается";failure="Проверяется очистка ресурсов прежнего запуска.";
        AppLog.line("PACKET_RESTORE","Replacement helper; requested profile="+profile+"; previous_instance="+oldInstance);
        Thread retry=new Thread(()->{
            String previous="";long retryDelay=1000;
            while(restoreWanted(token)){
                try{
                    Future<Boolean> attempt=WORKER.submit(()->{
                        if(!restoreWanted(token))return true;
                        if(!PacketBridge.permitted()){
                            state="Восстановление ожидает активации";failure="Запрос работы сохранён. Стоп отменит восстановление.";return false;
                        }
                        try{stopChild();}
                        catch(RuntimeException error){
                            if(restoreWanted(token)){state="Восстановление ожидает очистки";failure="Прежние ресурсы ещё не освобождены. Повторная проверка выполняется автоматически.";}
                            throw error;
                        }
                        if(!restoreWanted(token))return true;
                        if(!PacketBridge.permitted()){
                            state="Восстановление ожидает активации";failure="Очистка подтверждена. Ожидается активация для нового запуска; Стоп отменит его.";return false;
                        }
                        state="Восстанавливается";failure="";
                        startProxy(profile,port,verbose,token,startId,true,udp,quic,discord);
                        if(restoreWanted(token)&&running){
                            AppLog.line("PACKET_RESTORED","profile="+profile+"; previous_instance="+oldInstance+"; instance="+activeInstance+"; old TCP connections require reconnect");
                        }
                        return !restoreWanted(token)||running;
                    });
                    if(attempt.get())return;
                    previous="";
                }catch(InterruptedException e){Thread.currentThread().interrupt();return;}
                catch(Exception e){
                    if(!restoreWanted(token))return;
                    String message=e.getCause()==null?e.toString():e.getCause().toString();
                    if(!message.equals(previous)){previous=message;AppLog.line("PACKET_RESTORE_WAIT",message);}
                }
                try{Thread.sleep(retryDelay);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
                retryDelay=Math.min(5000,retryDelay*2);
            }
        },"packet-restore-retry");retry.setDaemon(true);retry.start();
    }
    public static boolean hasRunRequest(){ProxyService service=instance;return service!=null&&service.owns()&&service.runRequested&&!service.stopRequested;}
    private void stopChild(){
        if(packetOwned||PacketBridge.hasPendingCleanup()){
            try{PacketBridge.stop();packetOwned=false;}catch(Exception e){throw new IllegalStateException("Остановка ещё не подтверждена: "+e.getMessage(),e);}
        }
        if(currentOwner==owner)running=false;
        if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();
    }
    private void confirmedStop(int startId){state="Остановлен";failure="";running=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(latestStartId);}
    private void stopForUser(long token,int startId){
        if(!wanted(token)||!stopRequested)return;
        try{stopChild();if(wanted(token))confirmedStop(startId);}
        catch(RuntimeException e){
            if(!wanted(token))return;
            running=false;state=PacketBridge.hasActiveWorker()?"Остановка ожидает очистки":"Остановка ожидает связи";
            failure="Стоп принят, автоматический запуск отменён. Очистка пакетных ресурсов будет проверена повторно.";
            AppLog.line("PACKET_STOP_WAIT",e.getMessage());notifyState("Остановка запрошена; ожидается подтверждение очистки");
            if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();
            Thread retry=new Thread(()->{
                long retryDelay=1000;
                while(wanted(token)&&stopRequested){try{Thread.sleep(retryDelay);}catch(InterruptedException interrupted){return;}
                    retryDelay=Math.min(5000,retryDelay*2);
                    if(!wanted(token)||!stopRequested)return;
                    Future<?> attempt=WORKER.submit(()->{
                        if(!wanted(token)||!stopRequested)return;
                        try{stopChild();if(wanted(token))confirmedStop(startId);}catch(RuntimeException ignored){}
                    });
                    try{attempt.get(20,TimeUnit.SECONDS);}catch(Exception ignored){}
                    if(!PacketBridge.hasPendingCleanup())return;
                }
            },"packet-stop-retry");retry.setDaemon(true);retry.start();
        }
    }
    private void fail(String message,Throwable error,int startId){
        runRequested=false;
        boolean stopped=true;try{stopChild();}catch(RuntimeException e){stopped=false;AppLog.error("Stop own engine",e);}
        if(!owns())return;
        boolean activationRequired=stopped&&error instanceof EmbeddedAdb.ActivationRequiredException;
        running=false;
        failure=message==null||message.isEmpty()?"Пакетный движок завершился":message;
        state=activationRequired?ACTIVATION_REQUIRED:"Ошибка";
        if(activationRequired)AppLog.line("ACTIVATION_REQUIRED",failure);
        else if(error!=null)AppLog.error("Packet operation",error);
        else AppLog.line("ERROR",failure);
        if(stopped){stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(startId);}else notifyState("Остановка ещё не подтверждена; нажми Стоп");
    }
    public static void requestFlowSnapshot(){ProxyService s=instance;if(s==null||!s.packetOwned)return;try{PacketBridge.refresh();}catch(Exception e){AppLog.line("METRICS","Packet state refresh unavailable");}}
    private void notifyState(String text){((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTICE,notification(text));}
    private void ensureChannel(NotificationManager manager){
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
            UiLanguage.text(this,"Локальный AntiDPI-прокси"),NotificationManager.IMPORTANCE_LOW));
    }
    static void refreshNotificationLanguage(){
        ProxyService proxy=instance;
        if(proxy==null||!proxy.owns())return;
        NotificationManager manager=(NotificationManager)proxy.getSystemService(NOTIFICATION_SERVICE);
        proxy.ensureChannel(manager);
        for(android.service.notification.StatusBarNotification notice:manager.getActiveNotifications()){
            if(notice.getId()==NOTICE){proxy.notifyState(proxy.lastNoticeText);return;}
        }
    }
    private Notification notification(String text){
        lastNoticeText=text;
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,ProxyService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_stat_proxy).setContentTitle("Igareck AntiDPI").setContentText(UiLanguage.text(this,text)).setContentIntent(open).setOngoing(true)
            .setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE).addAction(new Notification.Action.Builder(null,UiLanguage.text(this,"Остановить"),stop).build()).build();
    }
    public static String snapshot(){try{return new JSONObject().put("version",AppLog.VERSION).put("state",state).put("running",running).put("active_profile",activeId).put("backend","nfqws2")
        .put("udp_enabled",activeUdp).put("quic_profile",activeQuicId).put("quic_recipe_sha256",activeQuicHash).put("discord_profile",activeDiscordId).put("discord_recipe_sha256",activeDiscordHash).put("port",activePort).put("instance",activeInstance).put("recipe_number",activeRecipeNumber).put("recipe_sha256",activeRecipeHash).put("failure",failure)
        .put("youtube_scan",new JSONObject(TcpStrategyScan.YOUTUBE.summary())).put("discord_scan",new JSONObject(TcpStrategyScan.DISCORD.summary())).put("stop_requested",instance!=null&&instance.stopRequested).put("run_requested",hasRunRequest()).put("no_vpn_service",true).toString(2);}catch(Exception e){return "{}";}}
    private static boolean keepsFailureStatus(){return "Ошибка".equals(state)||ACTIVATION_REQUIRED.equals(state);}
    @Override public void onDestroy(){
        destroyed=true;runRequested=false;if(currentOwner==owner){if(TcpStrategyScan.busy())TcpStrategyScan.cancelActive();PacketBridge.cancelPending();}generation++;
        WORKER.execute(()->{if(currentOwner!=owner)return;try{
            stopChild();if(currentOwner==owner&&!keepsFailureStatus()){state="Остановлен";running=false;failure="";}
        }catch(RuntimeException e){if(currentOwner==owner&&!keepsFailureStatus()){state="Остановка ожидает связи";running=false;failure="Очистка собственных ресурсов ещё не подтверждена.";}AppLog.error("Service cleanup",e);}});
        if(connectivity!=null&&networkCallback!=null)try{connectivity.unregisterNetworkCallback(networkCallback);}catch(Exception ignored){}
        if(currentOwner==owner&&!keepsFailureStatus()){state=PacketBridge.hasPendingCleanup()?"Остановка ожидает связи":"Остановлен";running=false;}
        AppLog.line("SERVICE","Foreground service stopped; owner="+owner);super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
