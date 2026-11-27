/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.Gravity;
import android.widget.*;
import java.util.EnumMap;

public final class WebsiteActivity extends Activity {
    private static final int ACTIVATE=41;
    private final ScreenUi ui=new ScreenUi(this);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final EnumMap<WebTarget,ScanRow> rows=new EnumMap<>(WebTarget.class);
    private SharedPreferences prefs;
    private Button reset;
    private WebTarget pending;
    private final Runnable refresh=new Runnable(){
        @Override public void run(){redraw();handler.postDelayed(this,500);}
    };

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        prefs=ScreenUi.preferences(this);
        if(saved!=null)pending=WebTarget.fromKey(saved.getString("target"));
        LinearLayout page=ui.page("Проверка TCP-стратегий");
        ui.add(page,ui.center("Прямой доступ к сайтам без VPN. На время проверки отключите VPN-клиент.",13,ScreenUi.MUTED,false),10);
        for(WebTarget target:WebTarget.values()){
            ScanRow row=new ScanRow(target,ui.card(page),saved);
            rows.put(target,row);
        }
        reset=ui.button("Сбросить статистику",v->resetResults());
        ui.add(page,reset,12);
        LinearLayout info=ui.card(page);
        ui.paragraph(info,"Результаты выше относятся только к прямому доступу к сайту!\n"
            +"Проверка доступа на сайт не гарантирует полную работоспособность всех его компонентов при загрузке напрямую.\n"
            +"Работоспособность отличается от оператора к оператору.");
        ui.paragraph(info,"Первоочередная цель Igareck AntiDPI - это восстановление работы конфигураций VPN и мостов Tor.\n"
            +"Поэтому проверка сайтов никак не связана с успешностью подключения к конфигам!\n"
            +"Работа подписок VPN / мостов Tor и прямой доступ к сайтам - это 2 разные вещи!");
        ui.note(info,"VPN / Tor и рабочие стратегии для них проверяются отдельно.\n"
            +"Начинайте со стратегий Golden, они в самом начале списка.\n"
            +"В VPN-клиенте сравните число доступных серверов, а в Tor проверьте подключение и скорость выбранного моста.");
        ui.back(page);
        redraw();
    }

    private void resetResults(){
        if(TcpStrategyScan.busy())return;
        SharedPreferences.Editor edit=prefs.edit();
        for(WebTarget target:WebTarget.values()){
            edit.putLong(target.key+"_hidden_result",TcpStrategyScan.forTarget(target).reportUpdatedAt());
        }
        edit.apply();
        redraw();
    }

    private final class ScanRow {
        final Button action;
        final EditText range,parallel;
        final TextView results;
        ScanRow(WebTarget target,LinearLayout card,Bundle saved){
            TextView site=ui.center(target.label+".com",21,ScreenUi.ACCENT,true);
            range=ui.field("1–484",InputType.TYPE_CLASS_TEXT);
            String previousRange=prefs.getString(target.key+"_range","1-484");
            range.setText(saved==null?previousRange:saved.getString(target.key+"_range",previousRange));
            range.setContentDescription(ui.en("Диапазон TCP-стратегий для ")+target.label);
            parallel=ui.field("3",InputType.TYPE_CLASS_NUMBER);
            String previousParallel=Integer.toString(prefs.getInt(target.key+"_parallel",3));
            parallel.setText(saved==null?previousParallel:saved.getString(target.key+"_parallel",previousParallel));
            parallel.setContentDescription(ui.en("Число параллельных проверок для ")+target.label);
            action=ui.button("Проверить",true,v->runScan(target));
            action.setContentDescription(ui.en("Проверить доступ к ")+target.domain);
            LinearLayout row=ui.row();
            row.setGravity(Gravity.BOTTOM);
            LinearLayout inputs=ui.row();
            inputs.setGravity(Gravity.BOTTOM);
            ui.weight(inputs,input("Стратегии",range),1,8);
            ui.weight(inputs,input("Параллели",parallel),1,0);
            if(ui.roomy()){
                LinearLayout actionColumn=ui.column();
                ui.add(actionColumn,site,8);
                ui.add(actionColumn,action,0);
                ui.weight(row,actionColumn,1,8);
                ui.weight(row,inputs,1.25f,0);
                ui.add(card,row,4);
            }else{
                ui.add(card,site,6);
                ui.add(card,inputs,8);
                ui.add(card,action,4);
            }
            results=ui.text("",13,ScreenUi.GREEN);
            results.setTextIsSelectable(true);
            ui.add(card,results,3);
        }
        private LinearLayout input(String label,EditText edit){
            LinearLayout setup=ui.column();
            ui.add(setup,ui.text(label,12,ScreenUi.MUTED),2);
            ui.add(setup,edit,0);
            return setup;
        }
    }

    private void runScan(WebTarget target){
        TcpStrategyScan scan=TcpStrategyScan.forTarget(target);
        if(scan.isRunning()){
            startService(new Intent(this,ProxyService.class).setAction(ProxyService.SCAN_STOP).putExtra("target",target.key));
            return;
        }
        if(TcpStrategyScan.busy()||LogsActivity.isTesting()||transitioning())return;
        ScanRow row=rows.get(target);
        try{
            String range=row.range.getText().toString().trim();
            TcpIds.range(range);
            int parallel;
            try{parallel=Integer.parseInt(row.parallel.getText().toString().trim());}
            catch(NumberFormatException e){throw new IllegalArgumentException("Укажите число параллельных проверок от 1 до 5.");}
            if(parallel<1||parallel>TcpStrategyScan.MAX_PARALLEL)
                throw new IllegalArgumentException("Укажите число параллельных проверок от 1 до 5.");
            if(!PacketBridge.permitted()){
                pending=target;
                startActivityForResult(new Intent(this,ActivationActivity.class).putExtra("from_scan",true),ACTIVATE);
                return;
            }
            prefs.edit().putString(target.key+"_range",range).putInt(target.key+"_parallel",parallel).apply();
            boolean resumeProxy=ProxyService.hasRunRequest();
            startForegroundService(new Intent(this,ProxyService.class).setAction(ProxyService.SCAN_START)
                .putExtra("target",target.key).putExtra("range",range).putExtra("parallel",parallel));
            row.action.setEnabled(false);
            if(resumeProxy)ui.toast("Прокси возобновит работу после проверки.");
        }catch(Exception e){AppLog.error(target.label+" requested",e);ui.toast(e.getMessage());}
    }

    private boolean transitioning(){return "Запускается".equals(ProxyService.state)||"Останавливается".equals(ProxyService.state);}
    private void redraw(){
        boolean busy=TcpStrategyScan.busy(),canStart=!busy&&!transitioning()&&!LogsActivity.isTesting();
        reset.setEnabled(!busy);
        for(WebTarget target:WebTarget.values()){
            ScanRow row=rows.get(target);
            if(row==null)continue;
            TcpStrategyScan scan=TcpStrategyScan.forTarget(target);
            boolean running=scan.isRunning();
            row.action.setText(ui.en(running?"Остановить":"Проверить"));
            row.action.setContentDescription(ui.en(running?"Остановить проверку ":"Проверить доступ к ")+target.domain);
            row.action.setEnabled(running||canStart);
            row.range.setEnabled(!busy);row.parallel.setEnabled(!busy);
            boolean hidden=!running&&prefs.getLong(target.key+"_hidden_result",-1)==scan.reportUpdatedAt();
            String result=hidden?"":scan.display();
            row.results.setText(ui.en(result));
            row.results.setVisibility(result.isEmpty()?View.GONE:View.VISIBLE);
            row.results.setTextColor(ui.color(scan.results.isEmpty()?ScreenUi.ERROR:ScreenUi.GREEN));
        }
    }

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==ACTIVATE){
            WebTarget target=pending;
            pending=null;
            if(target!=null&&result==RESULT_OK)runScan(target);
        }
    }
    @Override protected void onSaveInstanceState(Bundle out){
        super.onSaveInstanceState(out);
        if(pending!=null)out.putString("target",pending.key);
        rows.forEach((target,row)->{
            out.putString(target.key+"_range",row.range.getText().toString());
            out.putString(target.key+"_parallel",row.parallel.getText().toString());
        });
    }
    @Override protected void onResume(){super.onResume();handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
}
