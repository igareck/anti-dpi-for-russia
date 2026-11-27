/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;

import android.app.Application;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

public final class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            UiModeManager uiMode = getSystemService(UiModeManager.class);
            if (uiMode != null) uiMode.setApplicationNightMode(UiModeManager.MODE_NIGHT_NO);
        }
        AppLog.init(this);
        ServiceRestoration.initialize(this);
        observeUsb();
        final Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            AppLog.error("CRASH " + thread.getName(), error);
            if (old != null) old.uncaughtException(thread, error);
        });
    }

    private void observeUsb() {
        String action="android.hardware.usb.action.USB_STATE";
        BroadcastReceiver observer=new BroadcastReceiver(){
            private String previous="";
            @Override public void onReceive(Context context,Intent intent){
                if(!action.equals(intent.getAction()))return;
                String next="connected="+intent.getBooleanExtra("connected",false)
                    +"; configured="+intent.getBooleanExtra("configured",false)
                    +"; mtp="+intent.getBooleanExtra("mtp",false)+"; adb="+intent.getBooleanExtra("adb",false);
                if(!previous.equals(next)){
                    if(!previous.isEmpty())ServiceRestoration.usbConfigurationChanged();
                    previous=next;
                    AppLog.line("USB_STATE",next+"; worker_link="+PacketBridge.hasActiveWorker()
                        +"; wireless_debugging="+EmbeddedAdb.wirelessEnabled());
                }
            }
        };
        try{
            IntentFilter filter=new IntentFilter(action);
            if(Build.VERSION.SDK_INT>=33)registerReceiver(observer,filter,Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(observer,filter);
        }catch(Exception e){
            AppLog.line("USB_STATE","USB observer unavailable: "+e.getClass().getSimpleName());
        }
    }
}
