/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import java.util.*;
public final class TcpIds {
 public static String canonical(String id){
  if(id==null)return null;String s=id.trim().toUpperCase(Locale.ROOT);
  return s.matches("[NTТ][1-9][0-9]{0,2}")?"T"+s.substring(1):s;
 }
 public static String display(String id){String s=canonical(id);return s!=null&&s.matches("T[1-9][0-9]{0,2}")?"TCP"+s.substring(1):s;}
 public static int[] range(String input){
  if(input==null||input.trim().isEmpty())throw new IllegalArgumentException("Укажи стратегию или диапазон, например 1-45 или T40");
  TreeSet<Integer> values=new TreeSet<>();String text=input.trim().replace('–','-').replace('—','-');
  for(String part:text.split(",",-1)){
   String[] ends=part.trim().split("-",-1);if(ends.length<1||ends.length>2)throw new IllegalArgumentException("Пример диапазона: 1-45, 60, T100-T120");
   int first=number(ends[0]),last=ends.length==1?first:number(ends[1]);
   if(first>last)throw new IllegalArgumentException("Начало диапазона должно быть не больше конца");
   for(int n=first;n<=last;n++)values.add(n);
  }
  int[] out=new int[values.size()];int i=0;for(int n:values)out[i++]=n;return out;
 }
 private static int number(String text){
  String s=canonical(text);if(s.startsWith("TCP"))s=s.substring(3);else if(s.startsWith("T"))s=s.substring(1);
  if(!s.matches("[0-9]{1,3}"))throw new IllegalArgumentException("Номера TCP-стратегий: 1–484");
  int n=Integer.parseInt(s);if(n<1||n>484)throw new IllegalArgumentException("Номера TCP-стратегий: 1–484");return n;
 }
 private TcpIds(){}
}
