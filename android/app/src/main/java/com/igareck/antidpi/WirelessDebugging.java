/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import android.content.Context;
import android.provider.Settings;

/** Read-only observation. Never enables debugging or writes global settings. */
final class WirelessDebugging {
 static int enabled(Context context){
  try{int value=Settings.Global.getInt(context.getContentResolver(),"adb_wifi_enabled",-1);return value==0||value==1?value:-1;}
  catch(Exception ignored){return -1;}
 }
 static String label(int enabled){return enabled==1?"включена":enabled==0?"выключена":"состояние недоступно";}
 static String summary(int enabled){
  return enabled==1?"Отладка по Wi-Fi Android включена.":enabled==0?"Отладка по Wi-Fi Android выключена.":"Состояние отладки по Wi-Fi Android недоступно.";
 }
 static String unavailable(int enabled){
  if(enabled==0)return "Включите «Отладка по Wi-Fi» и повторите активацию. Если сопряжение сохранено, используйте его.";
  if(enabled==1)return "Повторите автопоиск или введите текущий порт из основной строки «IP-адрес и порт».";
  return "Проверьте переключатель отладки Android и текущий порт подключения. Вводите новый код, если Android удалил сопряжение.";
 }
 static String guidance(boolean worker,int enabled){
  if(worker)return enabled==0?"Движок и «Стоп» работают через локальный канал. Отладка потребуется для нового запуска модуля.":"Движок и «Стоп» работают через локальный канал.";
  return enabled==0?unavailable(enabled):"Для запуска нажмите «Активировать сетевой модуль». После перезапуска отладки порт может измениться.";
 }
 private WirelessDebugging(){}
}
