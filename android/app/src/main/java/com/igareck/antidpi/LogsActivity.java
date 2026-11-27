/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

public final class LogsActivity extends Activity {
    private static final int EXPORT=40;
    private static final AtomicBoolean TESTING=new AtomicBoolean();
    private final ScreenUi ui=new ScreenUi(this);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private Diagnostics.Cancellation cancellation;
    private Button check;
    private TextView traffic,udp;
    private SharedPreferences prefs;
    private final Runnable refresh=new Runnable(){
        @Override public void run(){redraw();handler.postDelayed(this,750);}
    };
    static boolean isTesting(){return TESTING.get();}

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        prefs=ScreenUi.preferences(this);
        render();
    }

    private void render(){
        LinearLayout page=ui.page("Логи и лицензии");
        LinearLayout logs=ui.card(page);
        ui.add(logs,ui.center("Логи",20,ScreenUi.INK,true),6);
        ui.add(logs,ui.center("Сохраните ZIP с журналами на устройстве для диагностики. В журнале могут быть адреса соединений.",13,ScreenUi.MUTED,false),8);
        ui.add(logs,ui.button("Экспорт логов",true,v->exportLogs()),8);
        CheckBox verbose=ui.check("Подробный журнал",prefs.getBoolean("verbose",false));
        verbose.setOnCheckedChangeListener((v,checked)->prefs.edit().putBoolean("verbose",checked).apply());
        ui.add(logs,verbose,2);
        ui.note(logs,"Изменение подробности вступит в силу при следующем «Запустить / применить».");
        check=ui.button("Проверить трафик",v->testProxy());
        ui.add(logs,check,6);
        Button theme=ui.button(ui.t("Светлая/темная тема","Light/dark theme"),v->{
            ui.toggleTheme();
            render();
        });
        theme.setStateDescription(ui.isDark()?ui.t("Тёмная тема","Dark theme"):ui.t("Светлая тема","Light theme"));
        ui.add(logs,theme,6);
        traffic=ui.text("",12,ScreenUi.MUTED);ui.add(logs,traffic,4);
        udp=ui.text("",12,ScreenUi.MUTED);ui.add(logs,udp,0);

        LinearLayout licenses=ui.card(page);
        ui.add(licenses,ui.center("Лицензии",20,ScreenUi.INK,true),8);
        TextView notices=ui.text(asset("THIRD_PARTY_NOTICES.md"),13,ScreenUi.INK);
        notices.setTextIsSelectable(true);
        LinearLayout.LayoutParams noticeParams=new LinearLayout.LayoutParams(ui.wide()?ui.dp(500):-1,-2);
        noticeParams.gravity=android.view.Gravity.CENTER_HORIZONTAL;
        noticeParams.bottomMargin=ui.dp(10);
        licenses.addView(notices,noticeParams);
        ui.add(licenses,ui.button("Полные тексты лицензий",v->licenseList()),0);
        ui.back(page);
        redraw();
    }

    private String asset(String filename){
        try(InputStream in=getAssets().open(filename)){return new String(Catalogue.bytes(in),StandardCharsets.UTF_8);}
        catch(IOException error){AppLog.error("Read license",error);return ui.en("Не удалось открыть текст лицензии.");}
    }
    private void licenseList(){
        try{
            String[] names=getAssets().list("licenses");
            if(names==null||names.length==0){ui.toast("Тексты лицензий не найдены.");return;}
            Arrays.sort(names);
            new AlertDialog.Builder(this).setTitle(ui.en("Полные тексты лицензий")).setItems(names,(dialog,index)->
                new AlertDialog.Builder(this).setTitle(names[index]).setMessage(asset("licenses/"+names[index]))
                    .setPositiveButton(ui.en("Закрыть"),null).show()).setNegativeButton(ui.en("Закрыть"),null).show();
        }catch(IOException e){ui.toast("Не удалось открыть список лицензий.");}
    }

    private void exportLogs(){
        AppLog.line("USER","Diagnostic export requested");
        String name="Igareck_Android_logs_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date())+".zip";
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/zip").putExtra(Intent.EXTRA_TITLE,name);
        try{startActivityForResult(intent,EXPORT);}
        catch(Exception e){AppLog.error("Open export picker",e);ui.toast("Не удалось открыть выбор файла.");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=EXPORT||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        new Thread(()->{
            try{
                try(OutputStream out=getContentResolver().openOutputStream(uri,"w")){
                    if(out==null)throw new IOException("Файл недоступен");
                    AppLog.exportTo(out);
                }
                AppLog.line("EXPORT","Diagnostic ZIP written successfully");
                runOnUiThread(()->ui.toast("ZIP с журналами сохранён."));
            }catch(Exception e){AppLog.error("Export",e);runOnUiThread(()->ui.toast("Не удалось сохранить ZIP."));}
        },"log-export").start();
    }

    private void testProxy(){
        if(TcpStrategyScan.busy())return;
        if(TESTING.get()){if(cancellation!=null)cancellation.cancel();return;}
        LinearLayout dialog=ui.column();
        dialog.setPadding(ui.dp(20),ui.dp(8),ui.dp(20),ui.dp(8));
        ui.note(dialog,"Проверка скачивает до 1 МиБ по HTTPS. Результат относится к TCP.");
        Spinner route=new Spinner(this);
        ArrayAdapter<String> modes=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,
            new String[]{ui.en("Напрямую"),ui.en("Через AntiDPI"),ui.en("Через SOCKS5 клиента VPN/Tor"),ui.en("Сравнить три пути")});
        modes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        route.setAdapter(modes);
        route.setSelection(Math.max(0,Math.min(3,prefs.getInt("test_route",1))));
        ui.add(dialog,route,8);
        LinearLayout proxySettings=ui.column();
        ui.note(proxySettings,"Клиент VPN/Tor");
        Diagnostics.Client[] clients=Diagnostics.Client.values();
        String[] names=new String[clients.length];
        for(int i=0;i<clients.length;i++)names[i]=clients[i].label.isEmpty()?ui.en("Другой клиент"):clients[i].label;
        Spinner clientSelect=new Spinner(this);
        ArrayAdapter<String> clientNames=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,names);
        clientNames.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        clientSelect.setAdapter(clientNames);
        int lastClient=Math.max(0,Math.min(clients.length-1,prefs.getInt("diagnostic_client",0)));
        clientSelect.setSelection(lastClient);
        ui.add(proxySettings,clientSelect,8);
        ui.note(proxySettings,"Локальный порт SOCKS5 или Mixed в настройках клиента");
        EditText vpnPort=ui.field("",InputType.TYPE_CLASS_NUMBER);
        setClientPort(vpnPort,clients[lastClient]);
        ui.add(proxySettings,vpnPort,6);
        TextView proxyHint=ui.text("",13,ScreenUi.MUTED);
        ui.add(proxySettings,proxyHint,0);
        ui.add(dialog,proxySettings,8);
        proxyHint.setText(ui.en(clientPortHint(clients[lastClient])));
        onSelection(clientSelect, index->{
            setClientPort(vpnPort,clients[index]);
            proxyHint.setText(ui.en(clientPortHint(clients[index])));
        });
        proxySettings.setVisibility(usesClientProxy(route.getSelectedItemPosition())?View.VISIBLE:View.GONE);
        onSelection(route,index->proxySettings.setVisibility(usesClientProxy(index)?View.VISIBLE:View.GONE));
        ui.note(dialog,"HTTPS-адрес файла");
        EditText url=ui.field("",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(prefs.getString("test_url",Diagnostics.DEFAULT_URL));ui.add(dialog,url,8);
        ui.note(dialog,"Имя узла для замера (необязательно)");
        EditText node=ui.field("",InputType.TYPE_CLASS_TEXT);
        node.setText(prefs.getString("node_label",""));ui.add(dialog,node,8);
        CheckBox fallback=ui.check("При неудаче проверить второй HTTPS-адрес",prefs.getBoolean("diagnostic_fallback",true));
        ui.add(dialog,fallback,0);
        ScrollView scroll=new ScrollView(this);scroll.addView(dialog);
        new AlertDialog.Builder(this).setTitle(ui.en("Проверка передачи данных")).setView(scroll)
            .setNegativeButton(ui.en("Отмена"),null).setPositiveButton(ui.en("Начать"),(d,w)->{
                try{
                    int selected=route.getSelectedItemPosition();
                    boolean usesProxy=usesClientProxy(selected);
                    int vp=usesProxy?AdbBootstrap.port(vpnPort.getText().toString(),false):0;
                    Diagnostics.Client client=clients[clientSelect.getSelectedItemPosition()];
                    if((selected==1||selected==3)&&!ProxyService.running)throw new IllegalArgumentException("Сначала запустите AntiDPI.");
                    if(usesProxy&&ProxyService.running&&vp==ProxyService.activePort)throw new IllegalArgumentException("Укажите отдельный порт VPN-клиента.");
                    String address=url.getText().toString().trim(),nodeLabel=node.getText().toString().trim();
                    if(nodeLabel.length()>96||nodeLabel.contains("://")||nodeLabel.contains("@"))
                        throw new IllegalArgumentException("Укажите только короткое имя узла.");
                    boolean retry=fallback.isChecked();
                    SharedPreferences.Editor editor=prefs.edit().putInt("test_route",selected).putString("test_url",address)
                        .putString("node_label",nodeLabel).putBoolean("diagnostic_fallback",retry);
                    if(usesProxy)editor.putInt("diagnostic_client",client.ordinal()).putInt(client.portKey(),vp);
                    editor.apply();
                    if(TcpStrategyScan.busy()||!TESTING.compareAndSet(false,true))return;
                    Diagnostics.Cancellation cancel=new Diagnostics.Cancellation();
                    cancellation=cancel;
                    int antiDpiPort=ProxyService.activePort;
                    new Thread(()->{
                        try{Diagnostics.run(selected,antiDpiPort,vp,address,cancel,nodeLabel,retry,client);}
                        finally{TESTING.set(false);if(!isDestroyed())runOnUiThread(this::redraw);}
                    },"traffic-test").start();
                    redraw();
                }catch(Exception e){ui.toast(e.getMessage());}
            }).show();
    }
    private static boolean usesClientProxy(int route){return route==2||route==3;}
    private void setClientPort(EditText field,Diagnostics.Client client){
        int initial=client==Diagnostics.Client.KARING?prefs.getInt("vpn_port",client.suggestedPort):client.suggestedPort;
        int port=prefs.getInt(client.portKey(),initial);
        field.setText(port>=1024&&port<=65535?Integer.toString(port):"");
    }
    private static String clientPortHint(Diagnostics.Client client){
        if(client==Diagnostics.Client.EXCLAVE)return "В Exclave включите вход SOCKS5. Используйте его локальный порт, обычно 2080.";
        if(client==Diagnostics.Client.ORBOT||client==Diagnostics.Client.INVIZIBLE)
            return "Запустите Tor в выбранном клиенте и укажите порт его локального SOCKS5.";
        return "Укажите фактический порт клиента. Для проверки достаточно локального входа на 127.0.0.1.";
    }
    private static void onSelection(Spinner spinner,java.util.function.IntConsumer selected){
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> parent,View view,int index,long id){selected.accept(index);}
            @Override public void onNothingSelected(AdapterView<?> parent){}
        });
    }
    private void redraw(){
        check.setEnabled(!TcpStrategyScan.busy()&&(!TESTING.get()||cancellation!=null));
        check.setText(ui.en(TESTING.get()?"Отменить проверку":"Проверить трафик"));
        String result=Diagnostics.displaySummary();
        traffic.setText(ui.en(result));traffic.setVisibility(result.isEmpty()?View.GONE:View.VISIBLE);
        String metrics="";
        if(ProxyService.running&&ProxyService.activeUdp)try{
            JSONObject data=new JSONObject(PacketBridge.snapshot()).optJSONObject("metrics");
            if(data!=null)metrics="UDP · принято "+data.optLong("udp_local_rx")+" · отправлено "+data.optLong("udp_tx")
                +" · ответы "+data.optLong("udp_rx")+" · вызовы Discord/STUN "+data.optLong("voice_lua_calls");
        }catch(Exception ignored){}
        udp.setText(ui.en(metrics));udp.setVisibility(metrics.isEmpty()?View.GONE:View.VISIBLE);
    }
    @Override protected void onResume(){super.onResume();handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    @Override protected void onDestroy(){if(cancellation!=null)cancellation.cancel();super.onDestroy();}
}
