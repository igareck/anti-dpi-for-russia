/* Production Discord HTTPS and shared scanner; explicit local TLS/SOCKS fixture. */
package com.igareck.antidpi;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import android.content.*;
import org.json.*;

public final class DiscordTcpHarness {
 static int checks;static JSONArray cases=new JSONArray();
 static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static YoutubeProbe.Result attempt(SSLContext ctx,byte[] response,String url)throws Exception{
  try(YoutubeHarness.Fixture f=new YoutubeHarness.Fixture(ctx,response)){return YoutubeProbe.probe(f.server.getLocalPort(),new YoutubeProbe.Cancellation(),1800,url,ctx.getSocketFactory(),WebTarget.DISCORD,YoutubeHarness.LOOKUP);}
 }
 static void expect(SSLContext ctx,String name,byte[] data,String reason,boolean pass)throws Exception{
  YoutubeProbe.Result r=attempt(ctx,data,"https://discord.com/");check(r.pass==pass&&r.reason.equals(reason),name+": "+r.json());cases.put(new JSONObject().put("name",name).put("result",r.json()));
 }
 public static void main(String[] args)throws Exception{
  KeyStore store=KeyStore.getInstance("PKCS12");try(InputStream in=Files.newInputStream(Paths.get(args[0]))){store.load(in,"fixture-only".toCharArray());}
  KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(store,"fixture-only".toCharArray());TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(store);SSLContext ctx=SSLContext.getInstance("TLS");ctx.init(km.getKeyManagers(),tm.getTrustManagers(),new SecureRandom());
  String prefix="<!doctype html><html><title>Discord - Fixture</title><a href='https://discord.com/app'>Open</a><p>";
  byte[] body=(prefix+String.join("",Collections.nCopies(18000,"padding-123"))+"</p></html>").getBytes(StandardCharsets.UTF_8);
  expect(ctx,"Discord verified page",YoutubeHarness.normal(body),"THRESHOLD_REACHED",true);
  expect(ctx,"www redirect host supported",YoutubeHarness.normal(body),"THRESHOLD_REACHED",true);
  check(attempt(ctx,YoutubeHarness.normal(body),"https://www.discord.com/").pass,"www Discord certificate/host");
  expect(ctx,"YouTube markers do not prove Discord",YoutubeHarness.normal(("<html><title>YouTube</title>ytcfg.set"+String.join("",Collections.nCopies(15000,"padding"))).getBytes(StandardCharsets.UTF_8)),"NOT_DISCORD_HTML",false);
  expect(ctx,"generic block page",YoutubeHarness.normal(("<html><title>Blocked</title>Discord"+String.join("",Collections.nCopies(15000,"padding"))).getBytes(StandardCharsets.UTF_8)),"NOT_DISCORD_HTML",false);
  expect(ctx,"title alone rejected",YoutubeHarness.normal(("<html><title>Discord</title>"+String.join("",Collections.nCopies(15000,"padding"))).getBytes(StandardCharsets.UTF_8)),"NOT_DISCORD_HTML",false);
  expect(ctx,"short complete Discord",YoutubeHarness.normal(prefix.getBytes(StandardCharsets.UTF_8)),"SHORT_COMPLETE_RESPONSE",false);
  expect(ctx,"CAPTCHA",YoutubeHarness.normal((prefix+"<div class='hcaptcha'>verify</div>"+new String(body,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8)),"CAPTCHA_OR_CONSENT",false);
  expect(ctx,"HTTP 403",YoutubeHarness.response(403,"Content-Length: 0",new byte[0]),"HTTP_STATUS",false);
  expect(ctx,"support redirect rejected",YoutubeHarness.response(302,"Location: https://support.discord.com/\r\nContent-Length: 0",new byte[0]),"UNEXPECTED_FINAL_DOMAIN",false);
  expect(ctx,"foreign redirect rejected",YoutubeHarness.response(302,"Location: https://discord.com.evil/\r\nContent-Length: 0",new byte[0]),"UNEXPECTED_FINAL_DOMAIN",false);
  try(YoutubeHarness.Fixture f=new YoutubeHarness.Fixture(ctx,YoutubeHarness.normal(body))){f.redirect=true;YoutubeProbe.Result v=YoutubeProbe.probe(f.server.getLocalPort(),new YoutubeProbe.Cancellation(),2000,"https://discord.com/",ctx.getSocketFactory(),WebTarget.DISCORD,YoutubeHarness.LOOKUP);check(v.pass&&f.accepted.get()==2,"Fresh Discord redirect connections");}
  check(!WebTarget.DISCORD.allows(URI.create("http://discord.com/"))&&!WebTarget.DISCORD.allows(URI.create("https://user@discord.com/"))&&!WebTarget.DISCORD.allows(URI.create("https://discord.com.evil/")),"Discord redirect allowlist");
  for(WebTarget site:Arrays.asList(WebTarget.LINKEDIN,WebTarget.X,WebTarget.THREADS)){
   byte[] unrelated=("<html><title>"+site.label+"</title><a href='https://example.org/'>Unknown</a>").getBytes(StandardCharsets.UTF_8);
   byte[] challenge="<html><title>Just a moment</title><div id='cf-challenge'>Verify</div>".getBytes(StandardCharsets.UTF_8);
   check(YoutubeProbe.signature(unrelated,"",site).equals("NOT_"+site.name()+"_HTML"),"Unrelated page matched "+site);
   check(YoutubeProbe.signature(challenge,"",site).equals("CAPTCHA_OR_CONSENT"),"Challenge accepted for "+site);
   check(site.allows(URI.create(site.url))&&!site.allows(URI.create("https://"+site.domain+".example.org/")),"Redirect boundary for "+site);
  }
  check(WebTarget.LINKEDIN.allows(URI.create("https://ru.linkedin.com/"))&&WebTarget.THREADS.allows(URI.create("https://www.threads.net/")),"Canonical website redirects");
  String youtubeBefore=TcpStrategyScan.YOUTUBE.display();
  check(TcpStrategyScan.YOUTUBE.reserve(),"Reserve YouTube");check(!TcpStrategyScan.DISCORD.reserve(),"Discord started alongside YouTube");TcpStrategyScan.YOUTUBE.release();
  check(TcpStrategyScan.DISCORD.reserve(),"Reserve Discord");check(!TcpStrategyScan.YOUTUBE.reserve(),"YouTube started alongside Discord");TcpStrategyScan.YOUTUBE.release();check(TcpStrategyScan.DISCORD.isRunning(),"Wrong target released owner");
  AtomicInteger starts=new AtomicInteger(),stops=new AtomicInteger(),active=new AtomicInteger(),peak=new AtomicInteger();ConcurrentHashMap<Integer,AtomicInteger> attempts=new ConcurrentHashMap<>();
  TcpStrategyScan.Transport transport=new TcpStrategyScan.Transport(){public JSONObject start(int[] ns)throws Exception{starts.incrementAndGet();JSONObject ports=new JSONObject();for(int n:ns)ports.put("T"+n,n);return new JSONObject().put("youtube_ports",ports);}public void stop(){stops.incrementAndGet();}};
  TcpStrategyScan.DISCORD.run(TcpIds.range("TCP1-TCP30"),5,transport,()->true,(port,c)->{int n=active.incrementAndGet();peak.accumulateAndGet(n,Math::max);try{Thread.sleep(10);}catch(InterruptedException e){Thread.currentThread().interrupt();}active.decrementAndGet();int call=attempts.computeIfAbsent(port,k->new AtomicInteger()).incrementAndGet();YoutubeProbe.Result v=new YoutubeProbe.Result();v.pass=call==1||port%2==0;v.reason=v.pass?"THRESHOLD_REACHED":"TIMEOUT";return v;});
  JSONObject summary=new JSONObject(TcpStrategyScan.DISCORD.summary());check(summary.getString("target").equals("discord")&&summary.getString("url").equals("https://discord.com/")&&summary.getString("ending").equals("limit_10"),"Discord report identity/limit");
  check(summary.getJSONArray("results").length()==10&&starts.get()==2&&stops.get()==2&&peak.get()==5,"Discord ten-success cap and concurrency");check(TcpStrategyScan.DISCORD.results.contains("TCP")&&TcpStrategyScan.DISCORD.results.contains("(unstable)")&&TcpStrategyScan.DISCORD.results.contains("(pass)"),"Discord display labels/classifications");TcpStrategyScan.DISCORD.release();
  check(TcpStrategyScan.YOUTUBE.display().equals(youtubeBefore),"Discord overwrote YouTube results");
  check(TcpStrategyScan.DISCORD.reserve(),"Reserve failing Discord");TcpStrategyScan.DISCORD.run(new int[]{1},1,transport,()->true,(port,c)->new YoutubeProbe.Result());check(TcpStrategyScan.DISCORD.display().equals("Проверка не нашла стратегий, значит попробуйте вручную включать стратегии по одной и проверять работу Discord/VPN/Tor"),"Discord empty result message");TcpStrategyScan.DISCORD.release();
  check(TcpStrategyScan.DISCORD.reserve(),"Reserve cancellation");Thread scan=new Thread(()->TcpStrategyScan.DISCORD.run(TcpIds.range("1-30"),3,transport,()->true,(port,c)->{while(!c.stopped())try{Thread.sleep(10);}catch(InterruptedException e){Thread.currentThread().interrupt();}YoutubeProbe.Result v=new YoutubeProbe.Result();v.reason="CANCELLED";return v;}));scan.start();Thread.sleep(60);TcpStrategyScan.cancelActive();scan.join(1000);check(!scan.isAlive()&&new JSONObject(TcpStrategyScan.DISCORD.summary()).getString("ending").equals("cancelled"),"Discord manual cancellation");TcpStrategyScan.DISCORD.release();
  Context context=new Context();SharedPreferences fresh=context.getSharedPreferences("fresh-"+UUID.randomUUID(),0);ProxyOptions.migrate(fresh);check(!fresh.getBoolean("udp_enabled",false)&&!fresh.getBoolean("discord_enabled",false)&&fresh.getString("discord_profile","D1").equals("D1"),"Fresh install defaults");
  SharedPreferences legacy=context.getSharedPreferences("legacy-"+UUID.randomUUID(),0);legacy.edit().putBoolean("udp_enabled",true).putString("discord_profile","D5").apply();ProxyOptions.migrate(legacy);check(legacy.getBoolean("discord_enabled",false)&&legacy.getString("discord_profile","").equals("D5"),"Accepted D profile migration");
  legacy.edit().putBoolean("discord_enabled",false).putString("discord_profile","D7").apply();ProxyOptions.migrate(legacy);check(!legacy.getBoolean("discord_enabled",true)&&legacy.getString("discord_profile","").equals("D7"),"Persisted checkbox/profile reset on reopen");
  SharedPreferences removed=context.getSharedPreferences("removed-"+UUID.randomUUID(),0);removed.edit().putBoolean("udp_enabled",true).putString("discord_profile","D9").apply();ProxyOptions.migrate(removed);check(!removed.getBoolean("discord_enabled",true)&&removed.getString("discord_profile","").equals("D1"),"Removed D9 migration");
  check(DiscordProfiles.NAMES.length==8&&Arrays.stream(DiscordProfiles.NAMES).noneMatch(x->x.contains("D0")||x.contains("D9")||x.contains("D10")),"Removed UI profiles");
  for(String bad:Arrays.asList("D9","D10")){boolean rejected=false;try{DiscordProfiles.args(bad);}catch(IOException e){rejected=true;}check(rejected,"Removed profile accepted: "+bad);}
  check(ProxyOptions.appliedLabel("T1",false,"Q5","D0").equals("TCP1"),"TCP-only status");check(ProxyOptions.appliedLabel("T40",true,"Q5","D0").equals("TCP40 · Q5"),"Q-only status");check(ProxyOptions.appliedLabel("T20",true,"OFF","D3").equals("TCP20 · D3"),"D-only status");check(ProxyOptions.appliedLabel("T1",true,"Q5","D1").equals("TCP1 · Q5 · D1"),"Combined status");
  check(TcpStrategyScan.DISCORD.reserve(),"Reserve scoped cancellation");AtomicInteger scoped=new AtomicInteger();
  check(TcpStrategyScan.DISCORD.cancelWithTransport(()->{check(!TcpStrategyScan.YOUTUBE.reserve(),"Transport cancellation released owner early");scoped.incrementAndGet();}),"Owned target cancellation");TcpStrategyScan.DISCORD.release();
  check(TcpStrategyScan.YOUTUBE.reserve(),"Reserve next target");check(!TcpStrategyScan.DISCORD.cancelWithTransport(scoped::incrementAndGet)&&scoped.get()==1&&TcpStrategyScan.YOUTUBE.isRunning(),"Stale Discord Stop touched new YouTube transport");TcpStrategyScan.YOUTUBE.release();
  check(WebTarget.YOUTUBE.strategy(1).equals("TCP1")&&WebTarget.YOUTUBE.resultsLabel("T1 (pass), T484 (unstable), TCP2 (pass)").equals("TCP1 (pass), TCP484 (unstable), TCP2 (pass)"),"YouTube current and saved result labels");
  SharedPreferences oldOnly=context.getSharedPreferences("d-only-"+UUID.randomUUID(),0);oldOnly.edit().putBoolean("udp_enabled",false).putBoolean("discord_enabled",true).putString("discord_profile","D8").apply();ProxyOptions.migrate(oldOnly);check(!oldOnly.getBoolean("discord_enabled",true)&&oldOnly.getString("discord_profile","").equals("D8"),"D-only migration and remembered profile");
  check(ProxyOptions.quicId(false,true,"Q5").equals("Q5")&&!ProxyOptions.discordEnabled(false,true)&&ProxyOptions.discordEnabled(true,true)&&!ProxyOptions.discordEnabled(true,false)&&ProxyOptions.discordId(false,5).equals("D0")&&ProxyOptions.discordId(true,7).equals("D8"),"UDP master and optional Discord switches");
  System.out.println(new JSONObject().put("passed",true).put("checks",checks).put("network_cases",cases).put("max_parallel",peak.get()).put("discord_limit",summary));
 }
}
