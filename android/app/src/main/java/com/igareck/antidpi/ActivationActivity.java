/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.*;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.*;

/** Guided one-app wireless ADB activation with notification and manual fallbacks. */
public final class ActivationActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ScreenUi ui=new ScreenUi(this);
    private TextView status,discovered,description,debuggingStatus,cancelHint,automation;
    private EditText pairPort,connectPort,code;
    private Button pair,activate,search,cancel,release;
    private CheckBox autoDebug,restore;
    private final Runnable refresh=new Runnable(){
        @Override public void run(){draw();main.postDelayed(this,700);}
    };

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        EmbeddedAdb.init(this);
        PacketBridge.reattachSaved();
        LinearLayout page=ui.page("Активация сетевого модуля");
        LinearLayout state=ui.card(page);
        status=ui.center("",19,ScreenUi.AMBER,true);
        status.setAccessibilityHeading(false);ui.add(state,status,6);
        description=ui.text("",14,ScreenUi.INK);ui.add(state,description,6);
        debuggingStatus=ui.text("",13,ScreenUi.MUTED);ui.add(state,debuggingStatus,0);
        buildPairing(page);
        buildManual(page,saved);
        buildOptions(page);
        if(getIntent().getBooleanExtra("from_scan",false))ui.back(page,"Вернуться к проверкам");
        else ui.back(page);
        draw();
    }

    private void buildPairing(LinearLayout page){
        LinearLayout steps=ui.card(page);
        ui.add(steps,ui.heading("Выполняйте по порядку",18),8);
        ui.paragraph(steps,"1. Включите режим разработчика на Android 11 или новее. Для этого найдите «Номер сборки» в сведениях об устройстве и нажмите на него семь раз.");
        ui.paragraph(steps,"2. Включите Wi-Fi и подключитесь к точке доступа. В доверенной домашней сети отметьте «Всегда разрешать отладку в этой сети», когда Android покажет запрос.");
        ui.note(steps,"Для сопряжения достаточно подключения к локальной Wi-Fi-сети.");
        ui.paragraph(steps,"3. Откройте параметры разработчика. Найдите «Беспроводная отладка» или «Отладка по Wi-Fi» и включите её.");
        ui.add(steps,settingsButton(),9);
        ui.paragraph(steps,"4. Вернитесь сюда и разрешите уведомления для ввода кода. Если уведомление не всплывает, включите всплывающий показ в настройках канала или откройте шторку.");
        ui.add(steps,ui.button("Разрешить уведомления для ввода кода",v->allowNotifications()),6);
        ui.add(steps,ui.button("Настройки всплывающего уведомления",v->notificationSettings()),9);
        ui.paragraph(steps,"5. Нажмите «Начать сопряжение». Затем откройте отладку по Wi-Fi и выберите «Сопряжение с помощью кода».");
        search=ui.button("Начать сопряжение",true,v->{
            try{savePorts();allowNotificationsIfNeeded();send(AdbActivationService.SEARCH,null,0);}
            catch(Exception e){ui.toast(e.getMessage());}
        });
        ui.add(steps,search,6);
        ui.add(steps,settingsButton(),9);
        ui.paragraph(steps,"Оставьте системное окно с кодом открытым. В уведомлении Igareck введите шесть цифр и нажмите «Отправить». Вернитесь на этот экран и дождитесь статуса «Сетевой модуль активен».");
        discovered=ui.text("",12,ScreenUi.MUTED);ui.add(steps,discovered,0);
    }
    private Button settingsButton(){
        return ui.button("Параметры разработчика → Отладка по Wi-Fi",v->openSettings());
    }

    private void buildManual(LinearLayout page,Bundle saved){
        LinearLayout manual=ui.card(page);
        ui.add(manual,ui.heading("Ручной ввод",18),6);
        ui.paragraph(manual,"Если шаг 5 не сработал, откройте параметры разработчика вручную через «Настройки Android». Перейдите в раздел «Беспроводная отладка» («Отладка по Wi-Fi») и нажмите «Сопряжение с помощью кода». Введите шестизначный код и порт из этого окна в поля ниже. Оставьте окно Android открытым, например в режиме разделённого экрана.");
        pairPort=ui.field("Порт сопряжения · автоматически",InputType.TYPE_CLASS_NUMBER);
        if(saved!=null)pairPort.setText(saved.getString("pair_port",""));
        ui.add(manual,pairPort,8);
        code=ui.field("Код сопряжения · 6 цифр",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        code.setSaveEnabled(false);
        code.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        ui.add(manual,code,8);
        pair=ui.button("Сопрячь и активировать",true,v->pairManually());
        ui.add(manual,pair,10);
        ui.note(manual,"Порт подключения находится на основном экране «Отладка по Wi-Fi» в строке «IP-адрес и порт». В каждое поле порта введите только число после двоеточия.");
        connectPort=ui.field("Порт подключения · автоматически",InputType.TYPE_CLASS_NUMBER);
        int configured=EmbeddedAdb.configuredConnectPort();
        String previous=configured==0?"":Integer.toString(configured);
        connectPort.setText(saved==null?previous:saved.getString("connect_port",previous));
        ui.add(manual,connectPort,8);
        activate=ui.button("Активировать сетевой модуль",true,v->{
            try{savePorts();saveOptions();send(AdbActivationService.ACTIVATE,null,0);}
            catch(Exception e){ui.toast(e.getMessage());}
        });
        ui.add(manual,activate,6);
        ui.add(manual,ui.button("Очистить ручные порты и повторить",v->{
            pairPort.setText("");connectPort.setText("");
            EmbeddedAdb.configure(0,0);
            send(EmbeddedAdb.paired()?AdbActivationService.ACTIVATE:AdbActivationService.SEARCH,null,0);
        }),0);
    }

    private void buildOptions(LinearLayout page){
        LinearLayout options=ui.card(page);
        restore=ui.check("Автоматически восстанавливать активацию сетевого модуля",AutoActivation.restoreEnabled(this));
        ui.add(options,restore,2);
        ui.note(options,"После первой настройки сетевой модуль восстанавливается при открытии приложения, после перезагрузки и при появлении Wi-Fi. Стратегии запускаются кнопкой «Запустить / применить». После перезагрузки сначала разблокируйте устройство.");
        autoDebug=ui.check("Автоматически включать отладку для активации сетевого модуля",AutoActivation.automatic(this));
        ui.add(options,autoDebug,2);
        automation=ui.text("",13,ScreenUi.MUTED);ui.add(options,automation,7);
        ui.note(options,"Для запуска остановленного сетевого модуля требуется подключение к Wi-Fi или USB-отладка через компьютер. Если модуль уже активен, интернет может работать через мобильную сеть.");
        restore.setOnCheckedChangeListener((v,checked)->{
            AutoActivation.setRestoreEnabled(this,checked);
            ServiceRestoration.optionsChanged(this);
            if(checked)ServiceRestoration.visible(this);
        });
        autoDebug.setOnCheckedChangeListener((v,checked)->{saveOptions();ServiceRestoration.optionsChanged(this);});

        LinearLayout actions=ui.card(page);
        ui.add(actions,ui.button("Активация через USB (если нет Wi-Fi, при наличии ПК)",
            v->startActivity(new Intent(this,UsbActivationActivity.class))),12);
        release=ui.button("Остановить сетевой модуль",true,v->send(AdbActivationService.RELEASE,null,0));
        ui.add(actions,release,8);
        ui.note(actions,"Кнопка «Стоп» на главном экране останавливает стратегии, сохраняя готовый сетевой модуль. «Остановить сетевой модуль» также приостанавливает автовосстановление до следующего ручного запуска модуля или стратегии.");
        cancel=ui.button("Отменить поиск / активацию",v->send(AdbActivationService.CANCEL,null,0));
        ui.add(actions,cancel,7);
        cancelHint=ui.text("",13,ScreenUi.MUTED);ui.add(actions,cancelHint,0);
    }

    private void pairManually(){
        try{
            savePorts();
            String value=AdbBootstrap.pairingCode(code.getText().toString());
            int port=AdbBootstrap.port(pairPort.getText().toString(),true);
            code.setText("");
            send(AdbActivationService.PAIR,value,port);
        }catch(Exception e){ui.toast(e.getMessage());}
    }
    private void saveOptions(){AutoActivation.configure(this,autoDebug.isChecked());}
    private void savePorts()throws Exception{
        EmbeddedAdb.configure(AdbBootstrap.port(pairPort.getText().toString(),true),AdbBootstrap.port(connectPort.getText().toString(),true));
    }
    private void send(String action,String code,int port){
        Intent intent=new Intent(this,AdbActivationService.class).setAction(action).putExtra("pair_port",port);
        if(code!=null)intent.putExtra(AdbActivationService.CODE,code);
        startForegroundService(intent);
    }
    private void allowNotificationsIfNeeded(){
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},33);
    }
    private void allowNotifications(){
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            allowNotificationsIfNeeded();
        else ui.toast("Разрешение на уведомления уже доступно.");
    }
    private void notificationSettings(){
        AdbActivationService.ensureChannel(this);
        try{startActivity(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()).putExtra(Settings.EXTRA_CHANNEL_ID,AdbActivationService.CHANNEL));}
        catch(Exception e){ui.toast("Откройте настройки уведомлений Igareck в Android.");}
    }
    private void openSettings(){
        try{startActivity(new Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"));}
        catch(Exception e){try{startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));}
        catch(Exception unavailable){ui.toast("Откройте параметры разработчика в настройках Android.");}}
    }

    private void draw(){
        boolean ready=PacketBridge.hasActiveWorker(),busy=EmbeddedAdb.busy();
        status.setText(ui.en(EmbeddedAdb.status()));
        status.setTextColor(ui.color(ready?ScreenUi.GREEN:ScreenUi.AMBER));
        description.setVisibility(ready?View.GONE:View.VISIBLE);
        if(!ready)description.setText(ui.en(EmbeddedAdb.detail()));
        int wireless=EmbeddedAdb.wirelessEnabled();
        String debugging=ready?WirelessDebugging.summary(wireless):
            "Отладка по Wi-Fi Android · "+WirelessDebugging.label(wireless)+"\n"+WirelessDebugging.guidance(false,wireless);
        debuggingStatus.setText(ui.en(debugging));
        discovered.setText(ui.t("Автопоиск · сопряжение ","Discovery · pairing ")+foundPort(EmbeddedAdb.pairPort())+ui.t(" · подключение "," · connection ")+foundPort(EmbeddedAdb.connectPort()));
        automation.setText(ui.en(ServiceRestoration.describe(this)+"\n"+(AutoActivation.permission(this)
            ?"Автовключение отладки доступно. Разрешение сохраняется после перезагрузки."
            :"Разрешение автовключения будет подготовлено сетевым модулем после первого успешного запуска.")));
        boolean searching=busy||EmbeddedAdb.searching();
        cancel.setEnabled(searching);
        cancelHint.setText(ui.en(searching?"Отмена прервёт выполняющийся поиск или запуск.":"Для остановки стратегий используйте «Стоп» на главном экране."));
        release.setEnabled(ready&&!busy&&!ProxyService.running&&!ProxyService.hasRunRequest()&&!PacketBridge.hasPendingCleanup());
        pair.setEnabled(!busy);
        activate.setEnabled(!busy&&(EmbeddedAdb.paired()||ready));
        search.setEnabled(!busy);
        search.setText(ui.en(EmbeddedAdb.paired()?"Повторить сопряжение (если Android его удалил)":"Начать сопряжение"));
        setResult(ready?RESULT_OK:RESULT_CANCELED);
    }
    private String foundPort(int port){return port==0?ui.t("ожидание","waiting"):Integer.toString(port);}
    @Override protected void onSaveInstanceState(Bundle out){
        super.onSaveInstanceState(out);
        out.putString("pair_port",pairPort.getText().toString());
        out.putString("connect_port",connectPort.getText().toString());
    }
    @Override protected void onResume(){
        super.onResume();
        ServiceRestoration.visible(this);
        draw();
        main.postDelayed(refresh,700);
    }
    @Override protected void onPause(){main.removeCallbacks(refresh);super.onPause();}
}
