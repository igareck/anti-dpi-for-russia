/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;

import java.io.*;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** One foreground scan, sequential batches and up to five isolated TCP profiles. */
public final class TcpStrategyScan {
    public static final int MAX_SUCCESS=10,MAX_PARALLEL=5;
    private static final EnumMap<WebTarget,TcpStrategyScan> SCANS=new EnumMap<>(WebTarget.class);
    static {for(WebTarget target:WebTarget.values())SCANS.put(target,new TcpStrategyScan(target));}
    public static final TcpStrategyScan YOUTUBE=forTarget(WebTarget.YOUTUBE),DISCORD=forTarget(WebTarget.DISCORD);
    private static final Object OWNER=new Object();
    private static volatile TcpStrategyScan active;
    public final WebTarget target;
    public final String NONE;
    private static final String ORIGIN_METRIC="https_origin";
    public volatile boolean finished;
    public volatile String progress="",results="",error="";
    private volatile int dnsErrors,dnsInconclusive;
    private volatile YoutubeProbe.Cancellation cancel;
    private volatile boolean userCancelled;
    private volatile String httpFailures="";
    private volatile String originOnly="";
    private File report;
    private JSONObject summary=new JSONObject();
    public static TcpStrategyScan forTarget(WebTarget target){return SCANS.get(target);}
    public interface Transport {
        JSONObject start(int[] numbers)throws Exception;
        void stop()throws Exception;
        default boolean keepsTapBetweenBatches(){return false;}
    }
    interface Attempt {YoutubeProbe.Result probe(int port,YoutubeProbe.Cancellation cancel);}
    public synchronized void init(File files){report=new File(files,target.key+"_results.json");
        if(report.isFile())try(InputStream in=new FileInputStream(report)){summary=new JSONObject(new String(Catalogue.bytes(in),StandardCharsets.UTF_8));
            results=target.resultsLabel(summary.optString("successful_text"));error=summary.optString("error");dnsErrors=summary.optInt("dns_errors",0);dnsInconclusive=summary.optInt("dns_inconclusive",0);finished=true;progress=summary.optBoolean("finished")?summary.optString("progress"):"Предыдущая проверка была прервана";
            userCancelled="cancelled".equals(summary.optString("ending"))||!summary.optBoolean("finished");
            httpFailures=formatHttpFailures(countHttpFailures(summary.optJSONArray("results")));
            originOnly=originOnlyText(summary.optJSONArray("results"));
            int savedMinimum=summary.optInt("minimum_bytes",YoutubeProbe.MINIMUM_BYTES);
            if(target.testsOrigin()&&!ORIGIN_METRIC.equals(summary.optString("probe_metric")))
                progress+="\nПредыдущий результат относится к проверке HTML.";
            if(!target.testsOrigin()&&savedMinimum!=YoutubeProbe.MINIMUM_BYTES)
                progress+="\nПорог сохранённой проверки: "+savedMinimum/1024+" КиБ · текущий порог: "+YoutubeProbe.MINIMUM_BYTES/1024+" КиБ.";
        }catch(Exception ignored){}
    }
    public boolean reserve(){synchronized(OWNER){if(active!=null)return false;active=this;finished=false;results="";error="";httpFailures="";originOnly="";dnsErrors=0;dnsInconclusive=0;progress="Подготовка проверки "+target.label+"…";userCancelled=false;cancel=new YoutubeProbe.Cancellation();return true;}}
    public static boolean busy(){return active!=null;}
    public boolean isRunning(){return active==this;}
    boolean cancelledByUser(){return userCancelled;}
    public static void cancelActive(){synchronized(OWNER){TcpStrategyScan own=active;if(own!=null)own.cancel();}}
    public void cancel(){cancelWithTransport(()->{});}
    public boolean cancelWithTransport(Runnable transportCancel){synchronized(OWNER){
        if(active!=this)return false;userCancelled=true;YoutubeProbe.Cancellation c=cancel;if(c!=null)c.cancel();
        // Capture the current transport instance before release permits a new
        // scan or proxy restore. A delayed target Stop must not cancel that run.
        transportCancel.run();return true;
    }}
    public String display(){
        if(!results.isEmpty())return results;
        if(!error.isEmpty())return "Проверка прервана из-за ошибки: "+error;
        if(!finished)return "";
        if(!originOnly.isEmpty())return originOnly+
            (httpFailures.isEmpty()?"":"\nПолучены HTTP-отказы: "+httpFailures+". Эти ответы не засчитываются.");
        if(!httpFailures.isEmpty())return "Получены HTTP-отказы: "+httpFailures+". Эти ответы не засчитываются.";
        if(userCancelled)return "";
        if(dnsInconclusive>0||dnsErrors>0)
            return "Ошибки DNS помешали оценить часть стратегий. Повтори проверку.";
        return NONE;
    }
    public static String classify(int success,int completed){return completed<2?"cancelled":success==2?"pass":success==1?"unstable":"fail";}
    public void run(int[] numbers,int parallel,Transport transport,BooleanSupplier wanted){
        try(ScanDoh dns=new ScanDoh()){
            run(numbers,parallel,transport,wanted,(port,token)->YoutubeProbe.probe(port,token,target,dns),dns);
        }
    }
    void run(int[] numbers,int parallel,Transport transport,BooleanSupplier wanted,Attempt probe){
        run(numbers,parallel,transport,wanted,probe,null);
    }
    private void run(int[] numbers,int parallel,Transport transport,BooleanSupplier wanted,Attempt probe,ScanDoh dns){
        final YoutubeProbe.Cancellation token=cancel;
        JSONArray all=new JSONArray();List<String> successful=new ArrayList<>();int checked=0;long started=System.currentTimeMillis();String ending="complete";
        ExecutorService workers=Executors.newFixedThreadPool(Math.max(1,Math.min(MAX_PARALLEL,parallel)),r->{Thread t=new Thread(r,"website-probe");t.setDaemon(true);return t;});
        boolean keepTap=transport.keepsTapBetweenBatches();
        boolean touchedTransport=false;
        try{
            if(numbers.length==0||parallel<1||parallel>MAX_PARALLEL)throw new IllegalArgumentException("Нужны стратегии 1–484 и параллельность 1–5");
            save(numbers,parallel,started,all,successful,false,ending,checked);
            if(dns!=null&&wanted.getAsBoolean()&&!token.stopped()){
                progress="Проверка DoH для "+target.label+"…";
                dns.resolve(URI.create(target.probeUrl()).getHost(),token,System.nanoTime()+ScanDoh.LOOKUP_NS);
            }
            for(int offset=0;offset<numbers.length&&!token.stopped()&&wanted.getAsBoolean()&&successful.size()<MAX_SUCCESS;offset+=parallel){
                int[] batch=Arrays.copyOfRange(numbers,offset,Math.min(numbers.length,offset+parallel));List<Future<JSONObject>> futures=new ArrayList<>();
                try{
                    progress=target.label+": проверено "+checked+"/"+numbers.length+" · найдено "+successful.size()+"/10 · запуск "+batchText(batch);
                    touchedTransport=true;
                    JSONObject ports=transport.start(batch).getJSONObject("youtube_ports");
                    if(token.stopped()||!wanted.getAsBoolean())break;
                    CompletionService<JSONObject> completed=new ExecutorCompletionService<>(workers);
                    for(int n:batch){
                        final int port=ports.getInt("T"+n);
                        futures.add(completed.submit(()->checkStrategy(n,port,token,wanted,probe)));
                    }
                    int pending=batch.length;
                    while(pending>0&&!token.stopped()&&wanted.getAsBoolean()){
                        Future<JSONObject> done=completed.poll(250,TimeUnit.MILLISECONDS);if(done==null)continue;pending--;
                        JSONObject item=done.get();all.put(item);String classification=item.getString("classification");
                        JSONArray probes=item.getJSONArray("attempts");for(int i=0;i<probes.length();i++)if("DNS_ERROR".equals(probes.getJSONObject(i).optString("reason")))dnsErrors++;
                        if(!classification.equals("cancelled"))checked++;
                        if(classification.equals("inconclusive_dns"))dnsInconclusive++;
                        String verdict=classification.equals("one_pass_dns")?"pass один раз, DNS":classification;
                        boolean accepted=target.testsOrigin()?("pass".equals(classification)||"unstable".equals(classification)):
                            item.optInt("successful_attempts")>0&&!classification.equals("cancelled");
                        if(accepted)successful.add(target.strategy(Integer.parseInt(item.getString("strategy").substring(1)))+
                            " ("+verdict+")");
                        results=String.join(", ",successful);progress=target.label+": проверено "+checked+"/"+numbers.length+" · найдено "+successful.size()+"/10"+dnsNote();
                        AppLog.line(target.key.toUpperCase(Locale.ROOT)+"_RESULT",item.toString());save(numbers,parallel,started,all,successful,false,ending,checked);
                        if(successful.size()>=MAX_SUCCESS){ending="limit_10";token.cancel();break;}
                    }
                }finally{
                    for(Future<?> f:futures)if(!f.isDone())f.cancel(true);
                    if(!keepTap)transport.stop();
                }
            }
            if(userCancelled||!wanted.getAsBoolean())ending="cancelled";
        }catch(UnknownHostException e){
            if(userCancelled||!wanted.getAsBoolean())ending="cancelled";
            else{
                dnsErrors++;
                error=e.getMessage()+". Стратегии не оценивались.";
                ending="error";
            }
        }catch(Exception e){
            if(!userCancelled&&wanted.getAsBoolean()){error=e.getCause()==null?String.valueOf(e.getMessage()):String.valueOf(e.getCause().getMessage());ending="error";AppLog.error(target.label+" scan",e);}
            else ending="cancelled";
        }finally{
            token.cancel();workers.shutdownNow();
            try{workers.awaitTermination(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            if(keepTap&&touchedTransport)try{transport.stop();}catch(Exception cleanup){
                AppLog.error(target.label+" scan cleanup",cleanup);
                if(!ending.equals("error")&&!ending.equals("cancelled")){
                    error=String.valueOf(cleanup.getMessage());ending="error";
                }
            }
            results=String.join(", ",successful);
            progress=(ending.equals("cancelled")?"Проверка остановлена":ending.equals("error")?"Проверка прервана: "+error:ending.equals("limit_10")?"Найдено 10 стратегий — проверка завершена":"Проверка завершена")+" · проверено "+checked+"/"+numbers.length+dnsNote();
            httpFailures=formatHttpFailures(countHttpFailures(all));
            userCancelled="cancelled".equals(ending);
            finished=true;
            save(numbers,parallel,started,all,successful,true,ending,checked);
            // ProxyService clears busy after restoring/cleaning up the normal proxy.
        }
    }
    private static JSONObject checkStrategy(int number,int port,YoutubeProbe.Cancellation token,BooleanSupplier wanted,Attempt probe)throws Exception{
        JSONArray attempts=new JSONArray();
        int ok=0,valid=0,dns=0,origins=0;
        // Two usable measurements are required. A DoH failure happens before
        // the strategy is exercised, so allow one replacement inside the batch.
        for(int i=0;i<3&&valid<2&&!token.stopped()&&wanted.getAsBoolean();i++){
            YoutubeProbe.Result result=probe.probe(port,token);
            attempts.put(result.json());
            if(result.technical)throw new IOException("Ошибка проверки: "+result.reason+" · "+result.error);
            if(result.reason.equals("CANCELLED"))break;
            if(result.reason.equals("DNS_ERROR"))dns++;
            else{valid++;if(result.pass)ok++;if(result.originReachable)origins++;}
        }
        String verdict=classify(ok,valid);
        if(valid<2&&dns>0&&!token.stopped()&&wanted.getAsBoolean())verdict=ok>0?"one_pass_dns":"inconclusive_dns";
        return new JSONObject().put("strategy","T"+number).put("classification",verdict)
            .put("valid_attempts",valid).put("successful_attempts",ok).put("origin_attempts",origins).put("attempts",attempts);
    }
    public void release(){synchronized(OWNER){if(active==this)active=null;}}
    private String dnsNote(){
        return (dnsErrors==0?"":" · ошибок DNS: "+dnsErrors)+(dnsInconclusive==0?"":" · без оценки: "+dnsInconclusive);
    }
    private static String batchText(int[] numbers){StringJoiner s=new StringJoiner(", ");for(int n:numbers)s.add("T"+n);return s.toString();}
    private static Map<String,Integer> countHttpFailures(JSONArray records){
        Map<String,Integer> counts=new TreeMap<>();
        if(records==null)return counts;
        for(int i=0;i<records.length();i++){
            JSONObject record=records.optJSONObject(i);
            JSONArray attempts=record==null?null:record.optJSONArray("attempts");
            if(attempts==null)continue;
            for(int j=0;j<attempts.length();j++){
                JSONObject attempt=attempts.optJSONObject(j);
                if(attempt!=null&&"HTTP_STATUS".equals(attempt.optString("reason")))
                    counts.merge(Integer.toString(attempt.optInt("http")),1,Integer::sum);
            }
        }
        return counts;
    }
    private static String formatHttpFailures(Map<String,Integer> counts){
        StringJoiner result=new StringJoiner(", ");
        for(Map.Entry<String,Integer> code:counts.entrySet())result.add(code.getKey()+" ×"+code.getValue());
        return result.toString();
    }
    private String originOnlyText(JSONArray records){
        if(target!=WebTarget.FACEBOOK||records==null)return "";
        SortedSet<Integer> matches=new TreeSet<>();
        for(int i=0;i<records.length();i++){
            JSONObject row=records.optJSONObject(i);
            if(row==null||!"origin_only".equals(row.optString("classification")))continue;
            String strategy=row.optString("strategy");
            if(strategy.matches("T[1-9][0-9]{0,2}"))matches.add(Integer.parseInt(strategy.substring(1)));
        }
        if(matches.isEmpty())return "";
        StringJoiner names=new StringJoiner(", ");
        int shown=0;
        for(int number:matches){
            if(shown++==MAX_SUCCESS)break;
            names.add(target.strategy(number));
        }
        return "Facebook отвечает по HTTPS. Загрузка HTML не подтверждена. Стратегии с доступом к серверу: "+
            names+(matches.size()>MAX_SUCCESS?" … ("+matches.size()+")":"");
    }
    private synchronized void save(int[] numbers,int parallel,long started,JSONArray all,List<String> successful,boolean done,String ending,int checked){
        try{
            JSONArray range=new JSONArray();for(int n:numbers)range.put("T"+n);
            summary=new JSONObject().put("version",AppLog.VERSION).put("target",target.key).put("url",target.probeUrl()).put("started_ms",started).put("updated_ms",System.currentTimeMillis()).put("strategies",range)
                .put("probe_metric",target.testsOrigin()?ORIGIN_METRIC:"html")
                .put("parallel",parallel).put("success_limit",MAX_SUCCESS).put("attempts_per_strategy",2).put("max_attempts_per_strategy",3).put("minimum_bytes",target.testsOrigin()?0:YoutubeProbe.MINIMUM_BYTES).put("dns_mode","doh").put("dns_errors",dnsErrors).put("dns_inconclusive",dnsInconclusive)
                .put("http_errors",done?new JSONObject(countHttpFailures(all)):new JSONObject())
                .put("finished",done).put("ending",ending).put("checked",checked).put("successful_text",String.join(", ",successful)).put("progress",progress).put("error",error).put("results",all);
            if(report!=null){File tmp=new File(report.getPath()+".tmp");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(summary.toString(2).getBytes(StandardCharsets.UTF_8));out.getFD().sync();}if(!tmp.renameTo(report))throw new IOException("Cannot save "+target.label+" results");}
        }catch(Exception e){AppLog.line(target.key.toUpperCase(Locale.ROOT)+"_REPORT","Не удалось сохранить результат: "+e.getClass().getSimpleName());}
    }
    synchronized long reportUpdatedAt(){return summary.optLong("updated_ms");}
    public synchronized String summary(){return summary.toString();}
    private TcpStrategyScan(WebTarget target){this.target=target;NONE=target.testsOrigin()
        ?"Проверка не подтвердила HTTPS-доступ к серверу Facebook."
        :"Проверка не нашла стратегий, значит попробуйте вручную включать стратегии по одной и проверять работу "+target.label+"/VPN/Tor";}
}
