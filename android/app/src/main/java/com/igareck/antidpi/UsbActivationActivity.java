/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** USB setup and live status for the existing activation service. */
public final class UsbActivationActivity extends Activity {
    private static final String PLATFORM_TOOLS="https://developer.android.com/tools/releases/platform-tools";
    private static final String STEPS="1. Выполните первую команду в первой строке.\n"
        +"Если видите статус «unauthorized», то подтвердите USB-отладку на Android и проверьте снова.\n"
        +"2. Когда появится «device», нажмите кнопку выше «Запуск через USB» на Android.\n"
        +"3. Дождитесь статуса ожидания «Ожидается USB-команда» и\n"
        +"выполните вторую команду во второй строке.";
    private final ScreenUi ui=new ScreenUi(this);
    private final Handler main=new Handler(Looper.getMainLooper());
    private Button launch;
    private TextView status,detail;
    private boolean requested;
    private final Runnable refresh=new Runnable(){
        @Override public void run(){draw();main.postDelayed(this,700);}
    };

    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);
        EmbeddedAdb.init(this);
        requested=saved!=null&&saved.getBoolean("requested");
        LinearLayout page=ui.page("Активация сетевого модуля через USB");
        LinearLayout instructions=ui.card(page);
        status=ui.center("",16,ScreenUi.AMBER,true);
        status.setAccessibilityHeading(false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        ui.add(instructions,status,8);
        detail=ui.text("",13,ScreenUi.MUTED);
        detail.setVisibility(View.GONE);
        ui.add(instructions,detail,6);

        TextView prep=ui.text("Включите «Отладка по USB» в параметрах разработчика Android. Подключите устройство к компьютеру кабелем передачи данных, разблокируйте экран и подтвердите запрос «Разрешить отладку по USB?», когда он появится.",14,ScreenUi.INK);
        prep.setTypeface(Typeface.DEFAULT_BOLD);
        ui.add(instructions,prep,12);
        ui.add(instructions,ui.text("Скачайте Android SDK Platform-Tools для своей ОС и распакуйте архив целиком.",14,ScreenUi.INK),16);
        ui.link(instructions,"Скачать Android SDK Platform-Tools",PLATFORM_TOOLS);
        ui.paragraph(instructions,"Нажмите на кнопку «Запуск через USB», чтобы запустить процесс активации:");
        launch=ui.button("Запуск через USB",v->{
            startForegroundService(new Intent(this,AdbActivationService.class).setAction(AdbActivationService.USB));
            requested=true;
            launch.setEnabled(false);
        });
        ui.add(instructions,launch,10);
        ui.add(instructions,ui.text("После статуса «Ожидается USB-команда» у вас есть три минуты, чтобы выполнить команду запуска на компьютере.",13,ScreenUi.MUTED),36);
        ui.add(instructions,ui.heading("Пошаговые инструкции.",21),12);

        addCommands(instructions,"Windows","PowerShell",
            "Распакуйте ZIP-архив. Откройте обычный PowerShell в папке platform-tools с adb.exe. Для выполнения команд ADB права администратора не нужны.",
            UsbCommands.WINDOWS);
        addCommands(instructions,"Linux / macOS","Terminal",
            "Распакуйте архив, откройте терминал и перейдите в папку platform-tools.",
            UsbCommands.UNIX);
        TextView completion=ui.text("Дождитесь в Igareck AntiDPI статуса «Сетевой модуль активен».\nЗатем выберите и запустите стратегию.",14,ScreenUi.INK);
        completion.setTypeface(Typeface.DEFAULT_BOLD);
        ui.add(instructions,completion,24);
        ui.back(page,"Вернуться назад");
    }

    private void addCommands(LinearLayout parent,String title,String terminal,String intro,UsbCommands commands){
        ui.add(parent,ui.heading(title,20),6);
        ui.paragraph(parent,intro);
        LinearLayout block=ui.column();
        block.setPadding(ui.dp(10),0,ui.dp(10),ui.dp(10));
        block.setBackground(ui.shape(ScreenUi.TERMINAL,14,ScreenUi.BORDER));
        LinearLayout header=ui.row();
        ui.weight(header,ui.text(terminal,13,ScreenUi.MUTED),1,8);
        Button copy=ui.button("Копировать команду запуска",v->{
            ui.copy(title,commands.start());
            ui.toast("Команда запуска скопирована");
        });
        header.addView(copy,new LinearLayout.LayoutParams(-2,-2));
        ui.add(block,header,6);
        HorizontalScrollView scroll=new HorizontalScrollView(this);
        TextView code=ui.text(commands.devices()+"\n"+commands.start(),13,ScreenUi.INK);
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextIsSelectable(true);
        scroll.addView(code,new HorizontalScrollView.LayoutParams(-2,-2));
        ui.add(block,scroll,0);
        ui.add(parent,block,10);
        TextView steps=ui.text(STEPS,14,ScreenUi.INK);
        steps.setLineSpacing(0,1);
        ui.add(parent,steps,8);
    }

    private void draw(){
        boolean ready=PacketBridge.hasActiveWorker();
        boolean busy=EmbeddedAdb.busy();
        String current=ui.en(EmbeddedAdb.status());
        if(!current.contentEquals(status.getText()))status.setText(current);
        status.setTextColor(ui.color(ready?ScreenUi.GREEN:ScreenUi.AMBER));
        launch.setEnabled(!busy);
        detail.setVisibility(requested&&!ready&&!busy?View.VISIBLE:View.GONE);
        if(detail.getVisibility()==View.VISIBLE)detail.setText(ui.en(EmbeddedAdb.detail()));
    }

    @Override protected void onSaveInstanceState(Bundle out){
        super.onSaveInstanceState(out);
        out.putBoolean("requested",requested);
    }
    @Override protected void onResume(){super.onResume();main.post(refresh);}
    @Override protected void onPause(){main.removeCallbacks(refresh);super.onPause();}
}
