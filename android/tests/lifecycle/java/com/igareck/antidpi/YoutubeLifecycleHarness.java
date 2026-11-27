package com.igareck.antidpi;
import android.content.*;
import java.util.concurrent.*;
import org.json.*;
import rikka.shizuku.Shizuku;
/** Production foreground-service orchestration with explicit Binder/TAP fixtures. */
public final class YoutubeLifecycleHarness {
 static Intent scan(String range,int parallel){return new Intent().setAction(ProxyService.YOUTUBE_START).putExtra("range",range).putExtra("parallel",parallel);}
 public static void main(String[] args)throws Exception{
  try{
   LifecycleHarness.test("YouTube uses one packet session across batches and restores exact settings",()->{
    Shizuku.target.warmApply=true;
    ProxyService s=new ProxyService();s.onCreate();
    s.onStartCommand(LifecycleHarness.start("T1").putExtra("port",12080).putExtra("verbose",true).putExtra("udp",true).putExtra("quic","Q5").putExtra("discord","D1"),0,1);LifecycleHarness.barrier();
    s.onStartCommand(scan("1-5",3),0,2);
    LifecycleHarness.await(()->!TcpStrategyScan.busy()&&ProxyService.running&&Shizuku.target.starts==4);LifecycleHarness.barrier();
    FakeTransport t=Shizuku.target;LifecycleHarness.check(t.youtubeStarts==2&&t.stops==2&&t.profile.equals("T1")&&t.lastPort==12080&&t.lastVerbose&&t.lastUdp&&t.lastQuic.equals("Q5")&&t.lastDiscord.equals("D1"),"Saved settings lost: "+ProxyService.snapshot());
    LifecycleHarness.stopAndWait(s,3);s.onDestroy();LifecycleHarness.barrier();
   });
   LifecycleHarness.test("scan button cancellation restores old run",()->{
    ProxyService s=new ProxyService();s.onCreate();LifecycleHarness.startAndWait(s,"T40",1);FakeTransport t=Shizuku.target;
    t.startEntered=new CountDownLatch(1);t.startRelease=new CountDownLatch(1);s.onStartCommand(scan("1-45",5),0,2);LifecycleHarness.check(t.startEntered.await(2,TimeUnit.SECONDS),"Scan not entered");
    s.onStartCommand(new Intent().setAction(ProxyService.YOUTUBE_STOP),0,3);t.startRelease.countDown();
    LifecycleHarness.await(()->!TcpStrategyScan.busy()&&ProxyService.running&&t.starts==3);LifecycleHarness.check(t.profile.equals("T40")&&t.youtubeStarts==1,"Cancellation failed to restore");
    LifecycleHarness.stopAndWait(s,4);s.onDestroy();LifecycleHarness.barrier();
   });
   LifecycleHarness.test("global Stop during scan cancels restoration and cleans pending batch",()->{
    ProxyService s=new ProxyService();s.onCreate();LifecycleHarness.startAndWait(s,"T20",1);FakeTransport t=Shizuku.target;
    t.startEntered=new CountDownLatch(1);t.startRelease=new CountDownLatch(1);s.onStartCommand(scan("1-45",5),0,2);LifecycleHarness.check(t.startEntered.await(2,TimeUnit.SECONDS),"Scan not entered");
    s.onStartCommand(LifecycleHarness.stop(),0,3);t.startRelease.countDown();LifecycleHarness.barrier();
    LifecycleHarness.check(!TcpStrategyScan.busy()&&!ProxyService.running&&!ProxyService.hasRunRequest()&&!PacketBridge.hasPendingCleanup()&&t.starts==2,"Stop resumed proxy or leaked child");s.onDestroy();LifecycleHarness.barrier();
   });
   LifecycleHarness.test("standalone scan finishes with no proxy and validates input",()->{
    ProxyService s=new ProxyService();s.onCreate();s.onStartCommand(scan("0-999",9),0,1);LifecycleHarness.check(!TcpStrategyScan.busy()&&Shizuku.target.starts==0,"Invalid scan started helper");
    s.onStartCommand(scan("T20",1),0,2);LifecycleHarness.await(()->!TcpStrategyScan.busy());LifecycleHarness.barrier();
    LifecycleHarness.check(!ProxyService.running&&!PacketBridge.hasPendingCleanup()&&ProxyService.state.equals("Остановлен"),"Standalone scan kept resources");s.onDestroy();LifecycleHarness.barrier();
   });
   LifecycleHarness.test("Discord scan blocks YouTube start and ignores a foreign target Stop",()->{
    ProxyService service=new ProxyService();service.onCreate();LifecycleHarness.startAndWait(service,"T40",1);FakeTransport t=Shizuku.target;
    t.startEntered=new CountDownLatch(1);t.startRelease=new CountDownLatch(1);
    service.onStartCommand(scan("1-20",3).setAction(ProxyService.DISCORD_START),0,2);LifecycleHarness.check(t.startEntered.await(2,TimeUnit.SECONDS),"Discord scan not entered");
    LifecycleHarness.check(TcpStrategyScan.DISCORD.isRunning()&&!TcpStrategyScan.YOUTUBE.isRunning(),"Wrong target owner");
    service.onStartCommand(scan("1-20",5),0,3);service.onStartCommand(new Intent().setAction(ProxyService.YOUTUBE_STOP),0,4);
    LifecycleHarness.check(TcpStrategyScan.DISCORD.isRunning()&&t.youtubeStarts==1,"Foreign scan action interrupted Discord");
    service.onStartCommand(new Intent().setAction(ProxyService.DISCORD_STOP),0,5);t.startRelease.countDown();
    LifecycleHarness.await(()->!TcpStrategyScan.busy()&&ProxyService.running&&t.starts==3);LifecycleHarness.check(t.profile.equals("T40"),"Discord cancel did not restore TCP40");LifecycleHarness.stopAndWait(service,6);service.onDestroy();LifecycleHarness.barrier();
   });
   LifecycleHarness.test("UDP master rejects D-only intent and scan restore keeps UDP off",()->{
    ProxyService service=new ProxyService();service.onCreate();service.onStartCommand(LifecycleHarness.start("T1").putExtra("udp",false).putExtra("quic","Q5").putExtra("discord","D1").putExtra("discord_enabled",true),0,1);LifecycleHarness.barrier();
    FakeTransport t=Shizuku.target;LifecycleHarness.check(ProxyService.running&&!ProxyService.activeUdp&&t.lastQuic.equals("Q5")&&t.lastDiscord.equals("D0"),"D-only intent bypassed master UDP switch");
    service.onStartCommand(scan("TCP1",1).setAction(ProxyService.DISCORD_START),0,2);LifecycleHarness.await(()->!TcpStrategyScan.busy()&&ProxyService.running&&t.starts==3);
    LifecycleHarness.check(t.lastQuic.equals("Q5")&&t.lastDiscord.equals("D0"),"Scan restore bypassed master UDP switch");LifecycleHarness.stopAndWait(service,3);service.onDestroy();LifecycleHarness.barrier();
   });
   LifecycleHarness.test("additional sites use their own report and target-scoped cancellation",()->{
    ProxyService service=new ProxyService();service.onCreate();LifecycleHarness.startAndWait(service,"T40",1);
    FakeTransport transport=Shizuku.target;
    transport.startEntered=new CountDownLatch(1);transport.startRelease=new CountDownLatch(1);
    service.onStartCommand(scan("1-20",3).setAction(ProxyService.SCAN_START).putExtra("target","threads"),0,2);
    LifecycleHarness.check(transport.startEntered.await(2,TimeUnit.SECONDS),"Threads scan not entered");
    TcpStrategyScan threads=TcpStrategyScan.forTarget(WebTarget.THREADS);
    service.onStartCommand(new Intent().setAction(ProxyService.SCAN_STOP).putExtra("target","x"),0,3);
    LifecycleHarness.check(threads.isRunning()&&!TcpStrategyScan.forTarget(WebTarget.X).isRunning(),"Wrong active website");
    service.onStartCommand(new Intent().setAction(ProxyService.SCAN_STOP).putExtra("target","threads"),0,4);
    transport.startRelease.countDown();
    LifecycleHarness.await(()->!TcpStrategyScan.busy()&&ProxyService.running&&transport.starts==3);
    JSONObject report=new JSONObject(threads.summary());
    LifecycleHarness.check(report.getString("ending").equals("cancelled")&&report.getString("target").equals("threads")
        &&report.getInt("minimum_bytes")==102400&&transport.profile.equals("T40"),"Wrong report or proxy not restored");
    LifecycleHarness.stopAndWait(service,5);service.onDestroy();LifecycleHarness.barrier();
   });
   LifecycleHarness.test("Discord checkbox off keeps Q-only mode",()->{
    ProxyService service=new ProxyService();service.onCreate();service.onStartCommand(LifecycleHarness.start("T20").putExtra("udp",true).putExtra("quic","Q3").putExtra("discord","D5").putExtra("discord_enabled",false),0,1);LifecycleHarness.barrier();
    LifecycleHarness.check(ProxyService.running&&Shizuku.target.lastQuic.equals("Q3")&&Shizuku.target.lastDiscord.equals("D0"),"Disabled D was applied");LifecycleHarness.stopAndWait(service,2);service.onDestroy();LifecycleHarness.barrier();
   });
   LifecycleHarness.test("local DHCP setup failure is not an empty strategy search",()->{
    TcpStrategyScan s=TcpStrategyScan.YOUTUBE;LifecycleHarness.check(s.reserve(),"Cannot reserve standalone scan");
    int[] stops={0};s.run(new int[]{1,2},1,new TcpStrategyScan.Transport(){
     public JSONObject start(int[] n)throws Exception{throw new java.io.IOException("DHCP OFFER timeout");}
     public void stop(){stops[0]++;}
    },()->true);s.release();
    LifecycleHarness.check(s.display().contains("DHCP OFFER timeout")&&!s.display().equals(s.NONE),"Infrastructure error rendered as no strategies");
    LifecycleHarness.check(new JSONObject(s.summary()).getInt("checked")==0&&stops[0]==1,"Failed setup counted profiles or lost cleanup");
   });
   LifecycleHarness.test("saved infrastructure failure survives UI reload",()->{
    java.io.File dir=java.nio.file.Files.createTempDirectory("igareck-scan-error").toFile();
    java.nio.file.Files.writeString(new java.io.File(dir,"youtube_results.json").toPath(),new JSONObject().put("finished",true).put("error","DHCP OFFER timeout").put("successful_text","").put("progress","Проверка прервана").toString());
    TcpStrategyScan.YOUTUBE.init(dir);LifecycleHarness.check(TcpStrategyScan.YOUTUBE.display().contains("DHCP OFFER timeout"),"Reload lost technical failure");
   });
   LifecycleHarness.test("Facebook labels two verified HTTPS responses pass and one unstable",()->{
    TcpStrategyScan facebook=TcpStrategyScan.forTarget(WebTarget.FACEBOOK);
    java.io.File dir=java.nio.file.Files.createTempDirectory("igareck-scan-http").toFile();
    java.io.File saved=new java.io.File(dir,"facebook_results.json");
    JSONObject refused=new JSONObject().put("http",400).put("reason","HTTP_STATUS");
    JSONObject oldRow=new JSONObject().put("strategy","T12").put("classification","origin_only")
        .put("attempts",new JSONArray().put(refused).put(refused));
    java.nio.file.Files.writeString(saved.toPath(),new JSONObject().put("minimum_bytes",102400).put("finished",true)
        .put("results",new JSONArray().put(oldRow)).toString());
    facebook.init(dir);
    LifecycleHarness.check(facebook.display().contains("TCP12")&&facebook.display().contains("400 ×2")
       &&facebook.results.isEmpty()&&facebook.progress.contains("относится к проверке HTML"),"Old HTML report was misread as the HTTPS check");
    LifecycleHarness.check(facebook.reserve(),"Cannot reserve Facebook");
    try{
     java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
     facebook.run(new int[]{1,2,3},1,new TcpStrategyScan.Transport(){
      public JSONObject start(int[] numbers)throws Exception{return new JSONObject().put("youtube_ports",new JSONObject().put("T"+numbers[0],1080+numbers[0]));}
      public void stop(){}
     },()->true,(port,cancel)->{
      YoutubeProbe.Result answer=new YoutubeProbe.Result();
      answer.url=WebTarget.FACEBOOK.probeUrl();answer.tls="TLSv1.3";
      int call=calls.incrementAndGet();
      answer.pass=port==1081||(port==1082&&call==3);
      answer.originReachable=answer.pass;
      answer.http=answer.pass?200:400;answer.reason=answer.pass?"ORIGIN_REACHABLE":"HTTP_STATUS";
      return answer;
     });
     JSONObject report=new JSONObject(facebook.summary());
     LifecycleHarness.check(report.getJSONObject("http_errors").getInt("400")==3,"Missing HTTP refusals");
     LifecycleHarness.check(report.getInt("minimum_bytes")==0&&report.getString("url").endsWith("/robots.txt")
          &&report.getString("probe_metric").equals("https_origin"),"Wrong HTTPS report metric");
     String expected="TCP1 (pass), TCP2 (unstable)";
     LifecycleHarness.check(report.optString("successful_text").equals(expected)
          &&report.getJSONArray("results").getJSONObject(1).getString("classification").equals("unstable"),"Facebook labels do not match the HTTPS measurements");
     LifecycleHarness.check(facebook.display().equals(expected),"Live Facebook results contain an extra label");
     facebook.init(dir);
     LifecycleHarness.check(facebook.display().equals(expected),"Unexpected Facebook result after reloading");
    }finally{facebook.release();}
   });
   LifecycleHarness.test("UI language is saved without restarting the local proxy",()->{
    ProxyService service=new ProxyService();service.onCreate();LifecycleHarness.startAndWait(service,"T20",1);
    try{
     String instance=ProxyService.activeInstance;
     String text="Проверка остановлена · проверено 1/484";
     LifecycleHarness.check(!UiLanguage.english(service)&&UiLanguage.text(service,text).equals(text),"Russian is not the default language");
     UiLanguage.toggle(service);
     LifecycleHarness.check(UiLanguage.english(new Context())&&UiLanguage.text(service,text).equals("Check stopped · checked 1/484"),"English preference or scan translation missing");
     LifecycleHarness.check(UiLanguage.text(service,"Сопряжение сохранено").equals("Pairing saved"),"Missing activation translation");
     ProxyService.refreshNotificationLanguage();
     LifecycleHarness.check(ProxyService.running&&ProxyService.activeInstance.equals(instance)
         &&Shizuku.target.starts==1,"Changing the UI language restarted the proxy");
    }finally{
     if(UiLanguage.english(service))UiLanguage.toggle(service);
     LifecycleHarness.stopAndWait(service,2);service.onDestroy();LifecycleHarness.barrier();
    }
   });
   System.out.println(new JSONObject().put("passed",true).put("tests",LifecycleHarness.results));
  }catch(Throwable e){e.printStackTrace();System.exit(1);}finally{System.exit(0);}
 }
}
