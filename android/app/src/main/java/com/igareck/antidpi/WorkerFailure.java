/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import android.system.ErrnoException;

/** Bounded, allowlisted diagnostics; never serialize input, exception messages or keys. */
final class WorkerFailure {
 static final String PREFIX="IGARECK_WORKER_ERROR ";
 static boolean stage(String value){return value!=null&&value.matches("uid|session|input|context|engine|channel|receipt|ready|shell");}
 static String marker(String phase,Throwable error){
  Throwable cause=error;for(int i=0;i<8&&cause.getCause()!=null&&cause.getCause()!=cause;i++)cause=cause.getCause();
  String kind=cause.getClass().getSimpleName();if(!kind.matches("[A-Za-z][A-Za-z0-9_]{0,60}"))kind="Throwable";
  return PREFIX+(stage(phase)?phase:"shell")+" "+kind+" "+(cause instanceof ErrnoException?((ErrnoException)cause).errno:0);
 }
 static boolean valid(String line){return line!=null&&line.length()<160&&line.matches("IGARECK_WORKER_ERROR (uid|session|input|context|engine|channel|receipt|ready|shell) [A-Za-z][A-Za-z0-9_]{0,60} -?[0-9]{1,8}");}
 static String description(String line){
  if("IGARECK_ADB_UID_ERROR".equals(line))return "ADB не предоставил сетевому модулю права shell UID 2000. Экспортируй журнал запуска.";
  if(!valid(line))return "Сетевой модуль не подтвердил запуск. Экспортируй журнал для диагностики.";
  String[] p=line.split(" ");String phase=p[1];
  String label=phase.equals("session")?"отделение процесса":phase.equals("context")?"контекст Android":phase.equals("engine")?"подготовка движка":phase.equals("channel")?"канал управления":phase.equals("receipt")?"подтверждение Android":phase.equals("input")?"параметры запуска":phase.equals("uid")?"права службы":"запуск процесса";
  return "Сопряжение и подключение к ADB выполнены. Сбой сетевого модуля: "+label+" · "+p[2]+(p[3].equals("0")?"":" · errno "+p[3])+". Повторный код не нужен; экспортируй журнал.";
 }
 private WorkerFailure(){}
}
