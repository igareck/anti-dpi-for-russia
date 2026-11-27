/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import java.util.*;

/** DNS-SD identity includes the service role, name AND network, not just name. */
final class AdbDiscoveryState {
 static final class Ticket {
  final boolean pairing;final String key;int port;long resolvedOrder;
  Ticket(boolean pairing,String key){this.pairing=pairing;this.key=key;}
 }
 private final LinkedHashMap<String,Ticket> records=new LinkedHashMap<>();
 private long order;
 private static String key(boolean pairing,String name,String network){return (pairing?"P":"C")+"\n"+name.toLowerCase(Locale.ROOT)+"\n"+network;}
 Ticket found(boolean pairing,String name,String network){
  if(name==null||name.isEmpty()||name.length()>512)return null;
  String k=key(pairing,name,network);Ticket previous=records.get(k);
  if(previous==null&&records.size()>=32)return null;
  Ticket ticket=new Ticket(pairing,k);
  if(previous!=null){ticket.port=previous.port;ticket.resolvedOrder=previous.resolvedOrder;}
  records.put(k,ticket);return ticket;
 }
 boolean current(Ticket ticket){return ticket!=null&&records.get(ticket.key)==ticket;}
 boolean resolved(Ticket ticket,int port,boolean ownAddress){
  if(!current(ticket)||!ownAddress||port<1024||port>65535)return false;
  ticket.port=port;ticket.resolvedOrder=++order;return true;
 }
 void lost(boolean pairing,String name,String network){
  if(name==null)return;
  if(network.isEmpty()){String prefix=key(pairing,name,"");records.entrySet().removeIf(e->e.getKey().startsWith(prefix));}
  else records.remove(key(pairing,name,network));
 }
 int port(boolean pairing){
  Ticket latest=null;for(Ticket t:records.values())if(t.pairing==pairing&&t.port!=0&&(latest==null||t.resolvedOrder>latest.resolvedOrder))latest=t;
  return latest==null?0:latest.port;
 }
 void clear(){records.clear();}
}
